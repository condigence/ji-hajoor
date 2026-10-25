package com.jihajoor.dispatch.service;

import com.jihajoor.dispatch.config.DispatchProperties;
import com.jihajoor.dispatch.domain.DispatchSettings;
import com.jihajoor.dispatch.domain.DispatchSettingsValidationException;
import com.jihajoor.dispatch.domain.MatchingWeights;
import com.jihajoor.dispatch.domain.WeightValidationException;
import com.jihajoor.dispatch.service.fake.InMemoryDispatchSettingsStore;
import com.jihajoor.dispatch.service.fake.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link DispatchSettingsService} (Requirements 8.2, 8.4, 8.5, 8.8, 19.5): the
 * configured defaults apply until the first save, a save is written to the shared store, any
 * out-of-range value rejects the whole update without writing anything, and a save made through
 * another replica is picked up once this replica's cache expires.
 */
class DispatchSettingsServiceTest {

    private static final MatchingWeights NEW_WEIGHTS = MatchingWeights.of(0.40, 0.20, 0.20, 0.10, 0.10);
    private static final DispatchSettings ELSEWHERE =
            new DispatchSettings(NEW_WEIGHTS, 12.0, 3.0, 4, 90, 7, 10);

    private InMemoryDispatchSettingsStore store;
    private DispatchProperties properties;
    private MutableClock clock;
    private DispatchSettingsService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryDispatchSettingsStore();
        properties = new DispatchProperties(); // settings cache lifetime: 10 s
        clock = new MutableClock(Instant.parse("2026-10-18T10:00:00Z"));
        service = new DispatchSettingsService(store, properties, clock);
    }

    // ---- defaults ------------------------------------------------------------------------------

    @Test
    void currentReflectsTheDefaultsUntilTheFirstSave() {
        DispatchSettings current = service.current();

        assertThat(current.weights()).isEqualTo(MatchingWeights.DEFAULT);
        assertThat(current.initialRadiusKm()).isEqualTo(10.0);
        assertThat(current.radiusIncrementKm()).isEqualTo(5.0);
        assertThat(current.maxExpansionCycles()).isEqualTo(3);
        assertThat(current.offerTimeoutSeconds()).isEqualTo(60L);
        assertThat(current.offerBatchSize()).isEqualTo(3);
        assertThat(current.agencyFallbackMinutes()).isEqualTo(5L);
        assertThat(store.saved()).isEmpty();
    }

    @Test
    void theConfigurationChoosesTheDefaults() {
        properties.setOfferBatchSize(5);
        properties.setAgencyFallbackMinutes(8);

        assertThat(service.current().offerBatchSize()).isEqualTo(5);
        assertThat(service.current().agencyFallbackMinutes()).isEqualTo(8L);
    }

    @Test
    void aSavedRowWinsOverTheConfiguration() {
        store.saveElsewhere(ELSEWHERE);
        properties.setOfferBatchSize(5);

        assertThat(service.current()).isEqualTo(ELSEWHERE);
    }

    // ---- updates -------------------------------------------------------------------------------

    @Test
    void updateSavesTheSettingsAndWhoSavedThem() {
        DispatchSettings requested = new DispatchSettings(NEW_WEIGHTS, 7.5, 2.5, 5, 90, 4, 6);

        DispatchSettings applied = service.update(requested, "admin-1");

        assertThat(applied).isEqualTo(requested);
        assertThat(store.saved()).contains(requested);
        assertThat(store.lastUpdatedBy()).isEqualTo("admin-1");
        assertThat(service.current()).isEqualTo(requested);
    }

    @Test
    void theSavingReplicaUsesItsSaveAtOnce() {
        service.current(); // caches the defaults

        service.update(ELSEWHERE, "admin-1");

        assertThat(service.current()).isEqualTo(ELSEWHERE);
    }

    @Test
    void aPartialUpdateAppliesOnTopOfTheLatestSaveNotTheCache() {
        service.current(); // caches the defaults
        store.saveElsewhere(ELSEWHERE); // another replica saves meanwhile
        MatchingWeights weights = MatchingWeights.of(0.20, 0.20, 0.20, 0.20, 0.20);

        DispatchSettings applied = service.update(s -> s.withWeights(weights), "admin-2");

        // The other replica's wave size and fallback survive this weights-only change.
        assertThat(applied).isEqualTo(ELSEWHERE.withWeights(weights));
        assertThat(store.saved()).contains(ELSEWHERE.withWeights(weights));
    }

    @Test
    void boundaryValuesAreAccepted() {
        service.update(new DispatchSettings(NEW_WEIGHTS, 0.1, 0.0, 0, 15, 1, 1), null);
        assertThat(service.current().maxExpansionCycles()).isZero();

        service.update(new DispatchSettings(NEW_WEIGHTS, 50.0, 10.0, 10, 600, 10, 30), null);
        assertThat(service.current().offerTimeoutSeconds()).isEqualTo(600L);
        assertThat(service.current().offerBatchSize()).isEqualTo(10);
        assertThat(service.current().agencyFallbackMinutes()).isEqualTo(30L);
    }

    @Test
    void anyInvalidValueRejectsTheWholeUpdate() {
        DispatchSettings before = service.current();

        assertRejected(new DispatchSettings(NEW_WEIGHTS, 0.0, 5.0, 3, 60, 3, 5), "initialRadiusKm");
        assertRejected(new DispatchSettings(NEW_WEIGHTS, Double.NaN, 5.0, 3, 60, 3, 5), "initialRadiusKm");
        assertRejected(new DispatchSettings(NEW_WEIGHTS, 10.0, -1.0, 3, 60, 3, 5), "radiusIncrementKm");
        assertRejected(new DispatchSettings(NEW_WEIGHTS, 10.0, 5.0, -1, 60, 3, 5), "maxExpansionCycles");
        assertRejected(new DispatchSettings(NEW_WEIGHTS, 10.0, 5.0, 11, 60, 3, 5), "maxExpansionCycles");
        assertRejected(new DispatchSettings(NEW_WEIGHTS, 10.0, 5.0, 3, 14, 3, 5), "offerTimeoutSeconds");
        assertRejected(new DispatchSettings(NEW_WEIGHTS, 10.0, 5.0, 3, 601, 3, 5), "offerTimeoutSeconds");

        assertRejected(new DispatchSettings(NEW_WEIGHTS, 10.0, 5.0, 3, 60, 0, 5), "offerBatchSize");
        assertRejected(new DispatchSettings(NEW_WEIGHTS, 10.0, 5.0, 3, 60, 11, 5), "offerBatchSize");
        assertRejected(new DispatchSettings(NEW_WEIGHTS, 10.0, 5.0, 3, 60, 3, 0), "agencyFallbackMinutes");
        assertRejected(new DispatchSettings(NEW_WEIGHTS, 10.0, 5.0, 3, 60, 3, 31), "agencyFallbackMinutes");

        // Nothing saved — not even the (valid) weights that travelled with the bad value.
        assertThat(store.saved()).isEmpty();
        assertThat(service.current()).isEqualTo(before);
    }

    @Test
    void aChangeThatThrowsSavesNothing() {
        store.saveElsewhere(ELSEWHERE);

        assertThatThrownBy(() -> service.update(s -> {
            throw new WeightValidationException("weights must sum to exactly 1.0 but summed to 1.5");
        }, "admin-1")).isInstanceOf(WeightValidationException.class);

        assertThat(store.saved()).contains(ELSEWHERE);
        assertThat(store.lastUpdatedBy()).isNull();
    }

    // ---- caching and other replicas ------------------------------------------------------------

    @Test
    void readsTheStoreAtMostOncePerCacheLifetime() {
        service.current();
        service.current();
        clock.advance(Duration.ofSeconds(9));
        service.current();

        assertThat(store.loads()).isEqualTo(1);
    }

    @Test
    void anotherReplicasSaveIsSeenOnceTheCacheExpires() {
        assertThat(service.current()).isEqualTo(service.defaults());
        store.saveElsewhere(ELSEWHERE);

        clock.advance(Duration.ofSeconds(9));
        assertThat(service.current()).isEqualTo(service.defaults());

        clock.advance(Duration.ofSeconds(1));
        assertThat(service.current()).isEqualTo(ELSEWHERE);
    }

    @Test
    void aZeroCacheLifetimeReadsTheStoreOnEverySearch() {
        properties.setSettingsCacheTtl(Duration.ZERO);
        service.current();
        store.saveElsewhere(ELSEWHERE);

        assertThat(service.current()).isEqualTo(ELSEWHERE);
        assertThat(store.loads()).isEqualTo(2);
    }

    @Test
    void latestReadsTheStoreWhateverTheCacheHolds() {
        service.current(); // caches the defaults
        store.saveElsewhere(ELSEWHERE);

        assertThat(service.latest()).isEqualTo(ELSEWHERE);
        // ...and refreshes the cache for the searches that follow.
        assertThat(service.current()).isEqualTo(ELSEWHERE);
    }

    // ---- an unreadable store -------------------------------------------------------------------

    @Test
    void anUnreadableStoreKeepsTheSettingsReadLast() {
        service.update(ELSEWHERE, "admin-1");
        clock.advance(Duration.ofSeconds(11));
        store.failWith(new IllegalStateException("database down"));

        assertThat(service.current()).isEqualTo(ELSEWHERE);
        assertThat(service.latest()).isEqualTo(ELSEWHERE);
    }

    @Test
    void anUnreadableStoreBeforeAnyReadMeansTheDefaults() {
        store.failWith(new IllegalStateException("database down"));

        assertThat(service.current()).isEqualTo(service.defaults());

        // Retried one cache lifetime later, not on every search.
        store.recover();
        store.saveElsewhere(ELSEWHERE);
        assertThat(service.current()).isEqualTo(service.defaults());
        clock.advance(Duration.ofSeconds(10));
        assertThat(service.current()).isEqualTo(ELSEWHERE);
    }

    @Test
    void aSavedRowThatNoLongerValidatesIsIgnored() {
        store.saveElsewhere(ELSEWHERE.withOfferBatchSize(50));

        assertThat(service.current()).isEqualTo(service.defaults());
    }

    private void assertRejected(DispatchSettings settings, String field) {
        assertThatThrownBy(() -> service.update(settings, "admin-1"))
                .isInstanceOf(DispatchSettingsValidationException.class)
                .hasMessageContaining(field);
    }
}
