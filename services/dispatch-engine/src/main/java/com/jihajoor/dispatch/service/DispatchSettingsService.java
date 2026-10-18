package com.jihajoor.dispatch.service;

import com.jihajoor.dispatch.config.DispatchProperties;
import com.jihajoor.dispatch.domain.DispatchSettings;
import com.jihajoor.dispatch.domain.DispatchSettingsValidationException;
import com.jihajoor.dispatch.domain.MatchingWeights;
import com.jihajoor.dispatch.port.DispatchSettingsStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;

/**
 * Reads and applies the Admin Portal's dispatch rules (Requirements 8.2, 8.4, 8.5, 8.8, 19.5) at
 * runtime, without a restart, consistently across replicas.
 *
 * <p><strong>Where the rules live.</strong> A save is written to the {@link DispatchSettingsStore}
 * ({@code dispatch.dispatch_settings}), so it survives restarts and every replica reads the same
 * rules. Until an Admin first saves them, the rules are the configured {@code jihajoor.dispatch.*}
 * values and {@code MatchingWeights.DEFAULT} ({@link #defaults()}), so each environment chooses its
 * starting values; from the first save on, the saved row wins.
 *
 * <p><strong>Freshness.</strong> {@link DispatchService} takes one snapshot ({@link #current()})
 * when a search starts and keeps it for the whole search. To keep a database read off every search,
 * a replica reuses the rules it last read for {@code jihajoor.dispatch.settings-cache-ttl}
 * (default 10 s). So a save reaches the replica that served it at once, and every other replica's
 * searches that start more than one cache lifetime after the save commits (plus the time of one
 * read). A search already running finishes with the snapshot it began with. Only one thread per
 * replica re-reads at a time; the others keep using the previous rules meanwhile.
 *
 * <p><strong>When the database cannot be read</strong> (or holds rules that no longer validate),
 * the replica keeps the rules it last read — or the defaults, if it never read any — logs why, and
 * tries again one cache lifetime later. Dispatch never stops for want of its rules; the bound above
 * holds while the database is reachable.
 *
 * <p>Every value is validated before anything is written, so a rejected update changes nothing
 * (the Requirement 19.5 guarantee, extended to the radius parameters). Concurrent saves, from any
 * replica, are applied one after another by the store; the last one wins (see
 * {@code JpaDispatchSettingsStore}).
 */
@Service
public class DispatchSettingsService {

    private static final Logger log = LoggerFactory.getLogger(DispatchSettingsService.class);

    /** Upper bound on radius expansions: each cycle re-queries providers and re-offers. */
    public static final int MAX_EXPANSION_CYCLES = 10;

    /**
     * Offer-timeout bounds in seconds. Below 15 s a provider cannot realistically read and accept
     * the push; above 10 minutes a customer waits too long per declined provider, since the
     * timeout is also the provider lock TTL and the busy-provider wait (Requirements 8.5, 8.11).
     */
    public static final long MIN_OFFER_TIMEOUT_SECONDS = 15L;
    public static final long MAX_OFFER_TIMEOUT_SECONDS = 600L;

    /** Wave-size bounds: at least one provider per wave, and no more than a push burst should reach. */
    public static final int MIN_OFFER_BATCH_SIZE = 1;
    public static final int MAX_OFFER_BATCH_SIZE = 10;

    /**
     * Agency-fallback bounds in minutes. It must stay well inside the Booking Service's stalled-search
     * sweep ({@code jihajoor.booking.provider-search-timeout}, 60 minutes by default), which settles a
     * search the Dispatch Engine never reported back on.
     */
    public static final long MIN_AGENCY_FALLBACK_MINUTES = 1L;
    public static final long MAX_AGENCY_FALLBACK_MINUTES = 30L;

    private final DispatchSettingsStore store;
    private final DispatchProperties properties;
    private final Clock clock;

    /** Held while reading the store or replacing {@link #snapshot}, so the two never interleave. */
    private final ReentrantLock refreshLock = new ReentrantLock();

    /** The rules last read or saved here; {@code null} until the first read. */
    private volatile Snapshot snapshot;

    public DispatchSettingsService(DispatchSettingsStore store, DispatchProperties properties, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * The settings a search starting now uses: the cached rules while they are fresh, otherwise
     * read again from the store. While another thread is re-reading, the previous rules are
     * returned rather than waiting for it.
     */
    public DispatchSettings current() {
        Snapshot cached = snapshot;
        if (cached != null && cached.freshAt(clock.instant())) {
            return cached.settings();
        }
        if (cached != null) {
            if (!refreshLock.tryLock()) {
                return cached.settings();
            }
        } else {
            refreshLock.lock();
        }
        try {
            Snapshot latest = snapshot;
            Instant now = clock.instant();
            if (latest != null && latest.freshAt(now)) {
                return latest.settings();
            }
            return refresh(now, latest);
        } finally {
            refreshLock.unlock();
        }
    }

    /**
     * The settings as saved right now, read from the store whatever the cache holds — for the
     * Admin Portal, so the form shows a save made through another replica straight away. Falls back
     * like {@link #current()} when the store cannot be read.
     */
    public DispatchSettings latest() {
        refreshLock.lock();
        try {
            return refresh(clock.instant(), snapshot);
        } finally {
            refreshLock.unlock();
        }
    }

    /**
     * Validates and, only if every value is valid, saves {@code settings} as the new rules.
     *
     * @param updatedBy who is saving, for the audit columns; may be {@code null}
     * @return the now-active settings
     * @throws DispatchSettingsValidationException if a radius, cycle, timeout, wave-size or
     *                                             fallback value is out of range; nothing is changed
     */
    public DispatchSettings update(DispatchSettings settings, String updatedBy) {
        Objects.requireNonNull(settings, "settings must not be null");
        return update(current -> settings, updatedBy);
    }

    /**
     * Applies {@code change} to the rules saved right now — read afresh from the store, not from
     * this replica's cache — and, only if the result is valid, saves it. Use this for an update that
     * keeps some current values (the weights-only endpoint, a form that omits optional fields), so
     * it cannot undo a save made meanwhile through another replica.
     *
     * <p>{@code change} may itself throw to refuse the update (e.g.
     * {@link com.jihajoor.dispatch.domain.WeightValidationException}); nothing is changed then.
     *
     * @param updatedBy who is saving, for the audit columns; may be {@code null}
     * @return the now-active settings
     * @throws DispatchSettingsValidationException if the result has an out-of-range value
     */
    public DispatchSettings update(UnaryOperator<DispatchSettings> change, String updatedBy) {
        Objects.requireNonNull(change, "change must not be null");
        DispatchSettings saved = store.update(current -> {
            DispatchSettings next = change.apply(current);
            validate(next);
            return next;
        }, defaults(), updatedBy);
        refreshLock.lock();
        try {
            snapshot = new Snapshot(saved, clock.instant().plus(cacheTtl()));
        } finally {
            refreshLock.unlock();
        }
        MatchingWeights w = saved.weights();
        log.info("Dispatch settings saved by {}: weights distance={}, availability={}, rating={}, skill={},"
                        + " performance={}; initialRadiusKm={}, radiusIncrementKm={}, maxExpansionCycles={},"
                        + " offerTimeoutSeconds={}, offerBatchSize={}, agencyFallbackMinutes={}",
                updatedBy, w.distanceWeight(), w.availabilityWeight(), w.ratingWeight(), w.skillWeight(),
                w.performanceWeight(), saved.initialRadiusKm(), saved.radiusIncrementKm(),
                saved.maxExpansionCycles(), saved.offerTimeoutSeconds(), saved.offerBatchSize(),
                saved.agencyFallbackMinutes());
        return saved;
    }

    /**
     * The rules in force until an Admin first saves them: the configured
     * {@code jihajoor.dispatch.*} values and {@link MatchingWeights#DEFAULT}.
     */
    public DispatchSettings defaults() {
        return new DispatchSettings(
                MatchingWeights.DEFAULT,
                properties.getInitialRadiusKm(),
                properties.getRadiusIncrementKm(),
                properties.getMaxExpansionCycles(),
                properties.getOfferTimeoutSeconds(),
                properties.getOfferBatchSize(),
                properties.getAgencyFallbackMinutes());
    }

    /** Reads the store and caches the result; the caller holds {@link #refreshLock}. */
    private DispatchSettings refresh(Instant now, Snapshot previous) {
        DispatchSettings settings;
        try {
            settings = store.load().orElse(null);
            if (settings == null) {
                // Nothing saved yet: the configured defaults, used as configured (as before).
                settings = defaults();
            } else {
                validate(settings);
            }
        } catch (RuntimeException unreadable) {
            settings = previous != null ? previous.settings() : defaults();
            log.warn("Could not read the saved dispatch settings ({}); keeping the {} for another {}",
                    unreadable.toString(), previous != null ? "settings read last" : "configured defaults",
                    cacheTtl());
        }
        snapshot = new Snapshot(settings, now.plus(cacheTtl()));
        return settings;
    }

    private Duration cacheTtl() {
        Duration ttl = properties.getSettingsCacheTtl();
        return ttl == null || ttl.isNegative() ? Duration.ZERO : ttl;
    }

    /** Rules as read at some moment, reused until {@code expiresAt}. */
    private record Snapshot(DispatchSettings settings, Instant expiresAt) {

        boolean freshAt(Instant now) {
            return now.isBefore(expiresAt);
        }
    }

    private static void validate(DispatchSettings s) {
        if (!Double.isFinite(s.initialRadiusKm()) || s.initialRadiusKm() <= 0) {
            throw new DispatchSettingsValidationException(
                    "initialRadiusKm must be greater than 0 but was " + s.initialRadiusKm());
        }
        if (!Double.isFinite(s.radiusIncrementKm()) || s.radiusIncrementKm() < 0) {
            throw new DispatchSettingsValidationException(
                    "radiusIncrementKm must be 0 or greater but was " + s.radiusIncrementKm());
        }
        if (s.maxExpansionCycles() < 0 || s.maxExpansionCycles() > MAX_EXPANSION_CYCLES) {
            throw new DispatchSettingsValidationException("maxExpansionCycles must be in [0, "
                    + MAX_EXPANSION_CYCLES + "] but was " + s.maxExpansionCycles());
        }
        if (s.offerTimeoutSeconds() < MIN_OFFER_TIMEOUT_SECONDS
                || s.offerTimeoutSeconds() > MAX_OFFER_TIMEOUT_SECONDS) {
            throw new DispatchSettingsValidationException("offerTimeoutSeconds must be in ["
                    + MIN_OFFER_TIMEOUT_SECONDS + ", " + MAX_OFFER_TIMEOUT_SECONDS + "] but was "
                    + s.offerTimeoutSeconds());
        }
        if (s.offerBatchSize() < MIN_OFFER_BATCH_SIZE || s.offerBatchSize() > MAX_OFFER_BATCH_SIZE) {
            throw new DispatchSettingsValidationException("offerBatchSize must be in ["
                    + MIN_OFFER_BATCH_SIZE + ", " + MAX_OFFER_BATCH_SIZE + "] but was " + s.offerBatchSize());
        }
        if (s.agencyFallbackMinutes() < MIN_AGENCY_FALLBACK_MINUTES
                || s.agencyFallbackMinutes() > MAX_AGENCY_FALLBACK_MINUTES) {
            throw new DispatchSettingsValidationException("agencyFallbackMinutes must be in ["
                    + MIN_AGENCY_FALLBACK_MINUTES + ", " + MAX_AGENCY_FALLBACK_MINUTES + "] but was "
                    + s.agencyFallbackMinutes());
        }
    }
}
