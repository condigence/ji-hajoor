package com.jihajoor.rating.provider;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.jihajoor.rating.config.RatingProperties;
import com.jihajoor.rating.domain.AggregateCalculator;
import com.jihajoor.rating.domain.ProviderRatingSyncRepository;
import com.jihajoor.rating.domain.ProviderRatingSyncState;
import com.jihajoor.rating.domain.Review;
import com.jihajoor.rating.domain.ReviewRepository;
import com.jihajoor.rating.domain.ReviewerRole;

/**
 * Keeps the Provider Service's copy of each provider's aggregate rating current (Requirements 4.7,
 * 15.4): every change to the reviews that count toward a provider's aggregate is pushed through
 * {@link ProviderRatingPort} after it commits, and re-sent until the Provider Service confirms it.
 *
 * <p><strong>Which changes.</strong> {@code ReviewService} calls {@link #recordChange} on every
 * path that changes what a provider's aggregate is over: a customer review submitted unflagged, a
 * flagged customer review approved (by {@code /reviews/{id}/approval} or Admin Portal PUBLISH), and
 * a customer review removed (by {@code /reviews/{id}/removal} or Admin Portal REMOVE). There is no
 * restore: removal is final. Provider-to-customer reviews never reach this class; they do not count
 * toward any provider's aggregate.
 *
 * <p><strong>After commit, durably.</strong> {@link #recordChange} runs inside the review
 * transaction: it bumps the provider's change sequence in {@code provider_rating_sync} and
 * publishes {@link ProviderRatingChanged}. The push itself runs in {@link #onRatingChanged} only
 * after the commit, so the Provider Service never sees an aggregate over a review that then rolled
 * back, and no database transaction stays open across the HTTP call. The sequence bump commits with
 * the review change, so if the push fails, or the process dies first, the provider stays
 * <em>owed</em> ({@code change_sequence > pushed_sequence}) and {@code ProviderRatingSweeper}
 * re-sends it on its next run. This is the same "record the debt in the commit, settle it after"
 * shape as the Payment Service's wallet credit and {@code WalletCreditSweeper}.
 *
 * <p><strong>Why retries are safe.</strong> A push carries the whole aggregate, recomputed from the
 * committed reviews at the time of the push, never a delta, and the provider's change sequence. The
 * Provider Service sets rather than adds, and ignores a sequence lower than the one it holds. So a
 * duplicate push (sweeper and after-commit racing, several instances sweeping, a crash after the
 * Provider Service applied it) changes nothing, and an older aggregate arriving after a newer one is
 * dropped rather than overwriting it.
 *
 * <p>The aggregate is read after the sequence, in the same transaction: the reviews query then sees
 * at least every change up to that sequence, so the aggregate sent under a sequence is never older
 * than the sequence claims.
 *
 * <p>A permanent refusal (an unknown provider, an invalid aggregate) is logged and recorded as
 * pushed, so it is not re-sent every minute forever; the provider's next review change tries again.
 */
@Component
public class ProviderRatingSync {

    private static final Logger log = LoggerFactory.getLogger(ProviderRatingSync.class);

    private final ReviewRepository reviewRepository;
    private final ProviderRatingSyncRepository syncRepository;
    private final ProviderRatingPort providerRatingPort;
    private final ApplicationEventPublisher events;
    private final RatingProperties properties;
    private final AggregateCalculator aggregateCalculator;
    /**
     * Fresh transactions for the work after commit. In an AFTER_COMMIT listener the committed
     * transaction's resources are still bound to the thread, so a plain {@code REQUIRED} transaction
     * would join it and its writes would never commit; and a new persistence context guarantees the
     * reviews are read as committed rather than from the old context's cache.
     */
    private final TransactionOperations newTransaction;
    private final Clock clock;

    @Autowired
    public ProviderRatingSync(ReviewRepository reviewRepository,
                              ProviderRatingSyncRepository syncRepository,
                              ProviderRatingPort providerRatingPort,
                              ApplicationEventPublisher events,
                              RatingProperties properties,
                              PlatformTransactionManager transactionManager) {
        this(reviewRepository, syncRepository, providerRatingPort, events, properties,
                requiresNew(transactionManager), Clock.systemUTC());
    }

    /** Visible for testing: pins the clock and runs without a transaction manager. */
    public ProviderRatingSync(ReviewRepository reviewRepository,
                              ProviderRatingSyncRepository syncRepository,
                              ProviderRatingPort providerRatingPort,
                              ApplicationEventPublisher events,
                              RatingProperties properties,
                              TransactionOperations newTransaction,
                              Clock clock) {
        this.reviewRepository = reviewRepository;
        this.syncRepository = syncRepository;
        this.providerRatingPort = providerRatingPort;
        this.events = events;
        this.properties = properties;
        this.aggregateCalculator = new AggregateCalculator(
                properties.getRecentWindow(), properties.getRecentWeight(), properties.getOlderWeight());
        this.newTransaction = newTransaction;
        this.clock = clock;
    }

    private static TransactionOperations requiresNew(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    /**
     * Records, inside the caller's transaction, that the provider's counted reviews changed, and
     * schedules the push for after the commit. Call it after the review change has been saved.
     */
    public void recordChange(UUID providerId) {
        syncRepository.markChanged(providerId, clock.instant());
        events.publishEvent(new ProviderRatingChanged(providerId));
    }

    /**
     * Pushes the new aggregate once the change has committed. Never throws: the review is already
     * committed and its caller must see success; a failed push stays owed for the sweeper.
     * {@code fallbackExecution} pushes at once when there was no transaction to wait for.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRatingChanged(ProviderRatingChanged event) {
        try {
            push(event.providerId());
        } catch (RuntimeException e) {
            log.warn("Rating push for provider {} failed after commit; the sweeper will re-send it: {}",
                    event.providerId(), e.getMessage());
        }
    }

    /**
     * Recomputes the provider's aggregate from the committed reviews and sets it in the Provider
     * Service, if a change is owed.
     *
     * @return {@code true} when the Provider Service confirmed it; {@code false} when nothing was
     *         owed, or the push failed (it stays owed) or was refused for good (it is dropped)
     */
    public boolean push(UUID providerId) {
        ProviderRatingSnapshot snapshot = newTransaction.execute(status -> owedSnapshot(providerId));
        if (snapshot == null) {
            return false;
        }
        try {
            providerRatingPort.updateAggregateRating(providerId, snapshot);
        } catch (ProviderRatingUpdateException e) {
            if (e.isPermanent()) {
                log.error("Provider Service refused the rating {} (over {} review(s), sequence {}) for "
                                + "provider {} for good; it will not be re-sent until the next change: {}",
                        snapshot.average(), snapshot.count(), snapshot.sequence(), providerId, e.getMessage());
                markPushed(providerId, snapshot.sequence());
            } else {
                log.warn("Rating for provider {} (sequence {}) not confirmed; it stays owed and the sweeper "
                        + "will re-send it: {}", providerId, snapshot.sequence(), e.getMessage());
            }
            return false;
        }
        markPushed(providerId, snapshot.sequence());
        return true;
    }

    /**
     * Re-sends the ratings still owed whose last change is at least {@code provider-sync.sweep-min-age}
     * old, oldest first, at most {@code provider-sync.sweep-batch-size} per run. The minimum age keeps
     * the sweep clear of a push the after-commit listener is still making; overlapping would only
     * cost a duplicate push. Driven by {@code ProviderRatingSweeper}.
     *
     * @return the number of ratings the Provider Service confirmed
     */
    public int pushOwed() {
        RatingProperties.ProviderSync sync = properties.getProviderSync();
        Instant cutoff = clock.instant().minus(sync.getSweepMinAge());
        List<ProviderRatingSyncState> owed = newTransaction.execute(
                status -> syncRepository.findOwed(cutoff, PageRequest.of(0, sync.getSweepBatchSize())));
        int delivered = 0;
        for (ProviderRatingSyncState state : owed == null ? List.<ProviderRatingSyncState>of() : owed) {
            try {
                if (push(state.getProviderId())) {
                    delivered++;
                }
            } catch (RuntimeException e) {
                log.warn("Owed rating for provider {} could not be re-sent; will retry on the next sweep: {}",
                        state.getProviderId(), e.getMessage());
            }
        }
        return delivered;
    }

    /** The aggregate to send under the provider's current sequence, or {@code null} if none is owed. */
    private ProviderRatingSnapshot owedSnapshot(UUID providerId) {
        Optional<ProviderRatingSyncState> state = syncRepository.findById(providerId);
        if (state.isEmpty() || !state.get().isOwed()) {
            return null; // already delivered (by the sweeper or another push), or never changed
        }
        // Read after the sequence: see the class comment.
        List<Review> reviews = reviewRepository.findByRevieweeIdAndReviewerRole(
                providerId, ReviewerRole.CUSTOMER);
        return ProviderRatingSnapshot.of(aggregateCalculator.summarize(reviews, clock.instant()),
                state.get().getChangeSequence());
    }

    /**
     * Records the delivery. A failure here is logged, not thrown: the rating is already set, and the
     * worst case is that the sweeper sends it again, which the Provider Service absorbs.
     */
    private void markPushed(UUID providerId, long sequence) {
        try {
            newTransaction.executeWithoutResult(status -> syncRepository.markPushed(providerId, sequence));
        } catch (RuntimeException e) {
            log.warn("Rating for provider {} (sequence {}) was delivered but could not be marked so; the "
                    + "sweeper will send it again: {}", providerId, sequence, e.getMessage());
        }
    }
}
