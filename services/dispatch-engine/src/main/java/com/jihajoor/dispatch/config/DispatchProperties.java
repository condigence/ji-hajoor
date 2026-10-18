package com.jihajoor.dispatch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Externalised dispatch tuning parameters (Requirements 8.2, 8.5, 8.8), so operations can adjust
 * behaviour without a code change. Defaults match the design and requirements.
 *
 * <p>The radius, cycle, offer-timeout, wave-size and agency-fallback values are the
 * <em>defaults</em> of the Admin-tunable dispatch rules: they apply until an Admin first saves the
 * rules in the Admin Portal, after which the saved {@code dispatch.dispatch_settings} row wins on
 * every replica (see {@code DispatchSettingsService}). They are not changed at runtime. The poll
 * interval and the settings cache lifetime are read from here only.
 */
@ConfigurationProperties(prefix = "jihajoor.dispatch")
public class DispatchProperties {

    /** Initial search radius in kilometres (Requirement 8.2, default 10 km). */
    private double initialRadiusKm = 10.0;

    /** Radius increment added per expansion cycle in kilometres (Requirement 8.8, default 5 km). */
    private double radiusIncrementKm = 5.0;

    /** Maximum number of expansion cycles beyond the initial search (Requirement 8.8, default 3). */
    private int maxExpansionCycles = 3;

    /** Job-offer acceptance timeout in seconds; also the Redis lock TTL (Requirements 8.5, 8.11). */
    private long offerTimeoutSeconds = 60L;

    /**
     * How many providers are offered the job at the same time, best-ranked first (default 3). The
     * first to accept takes it and the other offers in the wave are withdrawn; when nobody in a
     * wave accepts, the next wave goes to the next-best providers.
     */
    private int offerBatchSize = 3;

    /**
     * How long, in minutes, providers are offered a booking before dispatch stops searching and
     * hands it to the Booking Service, which routes it to the partner agencies covering it
     * (AWAITING_ASSIGNMENT) or fails it when none does (default 5). Searching also stops earlier
     * when every radius cycle is exhausted.
     */
    private long agencyFallbackMinutes = 5L;

    /**
     * How often a waiting offer re-reads the provider's decision from Redis, in milliseconds. It
     * bounds how long an accept takes to reach the Booking Service, at one small read per interval
     * per in-flight offer.
     */
    private long offerPollIntervalMillis = 500L;

    /**
     * How long a replica reuses the dispatch rules it last read from the database before reading
     * them again (default 10 s). It bounds how long after an Admin save on another replica a new
     * search may still start with the previous rules; the replica that served the save uses the
     * new rules at once. Zero reads the row at the start of every search.
     */
    private Duration settingsCacheTtl = Duration.ofSeconds(10);

    public double getInitialRadiusKm() {
        return initialRadiusKm;
    }

    public void setInitialRadiusKm(double initialRadiusKm) {
        this.initialRadiusKm = initialRadiusKm;
    }

    public double getRadiusIncrementKm() {
        return radiusIncrementKm;
    }

    public void setRadiusIncrementKm(double radiusIncrementKm) {
        this.radiusIncrementKm = radiusIncrementKm;
    }

    public int getMaxExpansionCycles() {
        return maxExpansionCycles;
    }

    public void setMaxExpansionCycles(int maxExpansionCycles) {
        this.maxExpansionCycles = maxExpansionCycles;
    }

    public long getOfferTimeoutSeconds() {
        return offerTimeoutSeconds;
    }

    public void setOfferTimeoutSeconds(long offerTimeoutSeconds) {
        this.offerTimeoutSeconds = offerTimeoutSeconds;
    }

    public int getOfferBatchSize() {
        return offerBatchSize;
    }

    public void setOfferBatchSize(int offerBatchSize) {
        this.offerBatchSize = offerBatchSize;
    }

    public long getAgencyFallbackMinutes() {
        return agencyFallbackMinutes;
    }

    public void setAgencyFallbackMinutes(long agencyFallbackMinutes) {
        this.agencyFallbackMinutes = agencyFallbackMinutes;
    }

    public long getOfferPollIntervalMillis() {
        return offerPollIntervalMillis;
    }

    public void setOfferPollIntervalMillis(long offerPollIntervalMillis) {
        this.offerPollIntervalMillis = offerPollIntervalMillis;
    }

    public Duration getSettingsCacheTtl() {
        return settingsCacheTtl;
    }

    public void setSettingsCacheTtl(Duration settingsCacheTtl) {
        this.settingsCacheTtl = settingsCacheTtl;
    }
}
