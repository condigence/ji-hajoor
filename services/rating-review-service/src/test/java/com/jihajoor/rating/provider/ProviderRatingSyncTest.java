package com.jihajoor.rating.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import com.jihajoor.rating.config.RatingProperties;
import com.jihajoor.rating.domain.ProviderRatingSyncState;
import com.jihajoor.rating.domain.Review;
import com.jihajoor.rating.support.InMemoryProviderRatingSyncRepository;
import com.jihajoor.rating.support.InMemoryReviewRepository;
import com.jihajoor.rating.support.RecordingProviderRatingPort;

/**
 * Unit tests for {@link ProviderRatingSync}: a recorded change is pushed after commit with the
 * whole recomputed aggregate and the change sequence; a push that fails stays owed and the sweep
 * re-sends it; a permanent refusal is dropped; nothing is pushed twice for one change.
 */
class ProviderRatingSyncTest {

    private static final Instant NOW = Instant.parse("2024-06-01T12:00:00Z");

    private final InMemoryReviewRepository reviews = new InMemoryReviewRepository();
    private final InMemoryProviderRatingSyncRepository state = new InMemoryProviderRatingSyncRepository();
    private final RecordingProviderRatingPort port = new RecordingProviderRatingPort();
    private final RatingProperties properties = new RatingProperties();
    /** Events "published" by recordChange; delivered by the test, as the commit would. */
    private final List<Object> events = new ArrayList<>();

    private ProviderRatingSync syncAt(Instant now) {
        return new ProviderRatingSync(reviews, state, port, events::add, properties,
                TransactionOperations.withoutTransaction(), Clock.fixed(now, ZoneOffset.UTC));
    }

    private Review customerReview(UUID provider, int stars) {
        return reviews.save(Review.customerReview(UUID.randomUUID(), UUID.randomUUID(), provider,
                stars, stars, stars, stars, stars, null, null, false, NOW.minus(Duration.ofDays(1))));
    }

    @Test
    void recordingAChangePublishesItAndPushesNothingBeforeCommit() {
        UUID provider = UUID.randomUUID();
        customerReview(provider, 5);

        syncAt(NOW).recordChange(provider);

        assertThat(events).containsExactly(new ProviderRatingChanged(provider));
        assertThat(port.pushes(provider)).isEmpty();
        ProviderRatingSyncState row = state.findById(provider).orElseThrow();
        assertThat(row.getChangeSequence()).isEqualTo(1);
        assertThat(row.isOwed()).isTrue();
    }

    @Test
    void afterCommitTheWholeAggregateIsPushedUnderTheChangeSequence() {
        UUID provider = UUID.randomUUID();
        customerReview(provider, 5);
        customerReview(provider, 4);
        ProviderRatingSync sync = syncAt(NOW);
        sync.recordChange(provider);
        sync.recordChange(provider);

        sync.onRatingChanged(new ProviderRatingChanged(provider));

        assertThat(port.pushes(provider)).containsExactly(
                new ProviderRatingSnapshot(new BigDecimal("4.50"), 2, 2));
        assertThat(state.findById(provider).orElseThrow().getPushedSequence()).isEqualTo(2);
    }

    /** Both changes' listeners run; the second finds nothing owed and does not push again. */
    @Test
    void aChangeAlreadyDeliveredIsNotPushedAgain() {
        UUID provider = UUID.randomUUID();
        customerReview(provider, 3);
        ProviderRatingSync sync = syncAt(NOW);
        sync.recordChange(provider);

        assertThat(sync.push(provider)).isTrue();
        assertThat(sync.push(provider)).isFalse();

        assertThat(port.pushes(provider)).hasSize(1);
    }

    @Test
    void aFailedPushStaysOwedAndTheSweepDeliversItOnceOldEnough() {
        UUID provider = UUID.randomUUID();
        customerReview(provider, 2);
        ProviderRatingSync sync = syncAt(NOW);
        sync.recordChange(provider);
        port.failTransiently();

        // The listener swallows the failure: the review transaction has already committed.
        sync.onRatingChanged(new ProviderRatingChanged(provider));
        assertThat(state.findById(provider).orElseThrow().isOwed()).isTrue();

        port.recover();
        // Too recent: the after-commit push may still be in flight.
        assertThat(syncAt(NOW.plusSeconds(10)).pushOwed()).isZero();
        assertThat(port.pushes(provider)).isEmpty();

        assertThat(syncAt(NOW.plus(properties.getProviderSync().getSweepMinAge())).pushOwed()).isEqualTo(1);
        assertThat(port.last(provider)).isEqualTo(new ProviderRatingSnapshot(new BigDecimal("2.00"), 1, 1));
        assertThat(state.findById(provider).orElseThrow().isOwed()).isFalse();
    }

    @Test
    void aSweepWhileTheProviderServiceIsStillDownLeavesTheRatingOwed() {
        UUID provider = UUID.randomUUID();
        customerReview(provider, 4);
        syncAt(NOW).recordChange(provider);
        port.failTransiently();

        assertThat(syncAt(NOW.plus(Duration.ofMinutes(5))).pushOwed()).isZero();

        assertThat(state.findById(provider).orElseThrow().isOwed()).isTrue();
    }

    /** An unknown provider will never accept it: not re-sent every minute forever. */
    @Test
    void aPermanentRefusalIsDroppedUntilTheNextChange() {
        UUID provider = UUID.randomUUID();
        customerReview(provider, 4);
        ProviderRatingSync sync = syncAt(NOW);
        sync.recordChange(provider);
        port.refusePermanently();

        assertThat(sync.push(provider)).isFalse();

        assertThat(state.findById(provider).orElseThrow().isOwed()).isFalse();
        assertThat(syncAt(NOW.plus(Duration.ofHours(1))).pushOwed()).isZero();

        // The next change is attempted again.
        port.recover();
        sync.recordChange(provider);
        assertThat(sync.push(provider)).isTrue();
        assertThat(port.last(provider).sequence()).isEqualTo(2);
    }

    /** The V2 migration marks every already-reviewed provider owed; the sweep backfills them. */
    @Test
    void theSweepBackfillsProvidersOwedSinceTheMigration() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        customerReview(first, 5);
        customerReview(second, 1);
        customerReview(second, 3);
        state.put(new ProviderRatingSyncState(first, 1, 0, NOW.minus(Duration.ofMinutes(5))));
        state.put(new ProviderRatingSyncState(second, 1, 0, NOW.minus(Duration.ofMinutes(4))));

        assertThat(syncAt(NOW).pushOwed()).isEqualTo(2);

        assertThat(port.last(first)).isEqualTo(new ProviderRatingSnapshot(new BigDecimal("5.00"), 1, 1));
        assertThat(port.last(second)).isEqualTo(new ProviderRatingSnapshot(new BigDecimal("2.00"), 2, 1));
    }

    @Test
    void theSweepIsBounded() {
        properties.getProviderSync().setSweepBatchSize(2);
        for (int i = 0; i < 3; i++) {
            UUID provider = UUID.randomUUID();
            customerReview(provider, 4);
            state.put(new ProviderRatingSyncState(provider, 1, 0, NOW.minus(Duration.ofMinutes(10 - i))));
        }

        assertThat(syncAt(NOW).pushOwed()).isEqualTo(2);
        assertThat(syncAt(NOW).pushOwed()).isEqualTo(1);
    }

    /** Removed, flagged and provider-to-customer reviews do not count: all left out, count 0. */
    @Test
    void onlyCountedCustomerReviewsReachTheProviderService() {
        UUID provider = UUID.randomUUID();
        Review removed = customerReview(provider, 5);
        removed.deactivate();
        reviews.save(Review.customerReview(UUID.randomUUID(), UUID.randomUUID(), provider,
                1, 1, 1, 1, 1, null, null, true, NOW.minus(Duration.ofDays(1))));
        reviews.save(Review.providerReview(UUID.randomUUID(), UUID.randomUUID(), provider,
                2, null, null, false, NOW.minus(Duration.ofDays(1))));
        ProviderRatingSync sync = syncAt(NOW);
        sync.recordChange(provider);

        sync.push(provider);

        assertThat(port.last(provider)).isEqualTo(new ProviderRatingSnapshot(null, 0, 1));
    }

    @Test
    void aProviderWithNoRecordedChangeIsNotPushed() {
        assertThat(syncAt(NOW).push(UUID.randomUUID())).isFalse();
    }
}
