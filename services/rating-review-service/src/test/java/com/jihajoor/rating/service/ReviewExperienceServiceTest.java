package com.jihajoor.rating.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.jihajoor.rating.config.RatingProperties;
import com.jihajoor.rating.domain.Review;
import com.jihajoor.rating.domain.ReviewPrompt;
import com.jihajoor.rating.domain.ReviewerRole;
import com.jihajoor.rating.event.ReviewReminderEvent;
import com.jihajoor.rating.service.ReviewExperienceService.PromptView;
import com.jihajoor.rating.service.ReviewExperienceService.PublicReviews;
import com.jihajoor.rating.support.InMemoryReviewPromptRepository;
import com.jihajoor.rating.support.InMemoryReviewRepository;
import com.jihajoor.shared.outbox.OutboxEventPublisher;

/** Review prompts, reminders, a worker's public reviews and their replies. */
class ReviewExperienceServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");

    private final UUID customer = UUID.randomUUID();
    private final UUID worker = UUID.randomUUID();

    private InMemoryReviewRepository reviews;
    private InMemoryReviewPromptRepository prompts;
    private OutboxEventPublisher outbox;
    private ReviewExperienceService service;

    @BeforeEach
    void setUp() {
        reviews = new InMemoryReviewRepository();
        prompts = new InMemoryReviewPromptRepository();
        outbox = mock(OutboxEventPublisher.class);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(inv -> inv.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
        service = new ReviewExperienceService(reviews, prompts, new RatingProperties(), outbox, tx,
                Duration.ofHours(24), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ReviewPrompt prompt(UUID booking, Instant openedAt) {
        return prompts.save(ReviewPrompt.open(booking, UUID.randomUUID(), ReviewerRole.CUSTOMER, customer, worker,
                openedAt, openedAt.plus(Duration.ofDays(7))));
    }

    private Review review(int stars, boolean flagged, Instant at) {
        return reviews.save(Review.customerReview(UUID.randomUUID(), UUID.randomUUID(), worker, stars, stars, stars,
                stars, stars, "Good work " + stars, "203.0.113.1", flagged, at));
    }

    @Test
    void promptsShowWhatIsOpenReviewedOrExpired() {
        UUID open = UUID.randomUUID();
        UUID done = UUID.randomUUID();
        UUID expired = UUID.randomUUID();
        prompt(open, NOW.minus(Duration.ofHours(2)));
        ReviewPrompt reviewed = prompt(done, NOW.minus(Duration.ofDays(1)));
        reviewed.markFulfilled();
        reviews.save(Review.customerReview(done, customer, worker, 4, 4, 4, 4, 4, "ok", null, false, NOW));
        prompt(expired, NOW.minus(Duration.ofDays(8)));

        assertThat(service.promptsFor(customer))
                .extracting(PromptView::bookingId, PromptView::status, PromptView::myRating)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(open, "OPEN", null),
                        org.assertj.core.groups.Tuple.tuple(done, "REVIEWED", 4),
                        org.assertj.core.groups.Tuple.tuple(expired, "EXPIRED", null));
        assertThat(service.promptsFor(UUID.randomUUID())).isEmpty();
    }

    @Test
    void aCustomerIsRemindedOnceADayAfterPaying_andNeverAfterReviewing() {
        ReviewPrompt due = prompt(UUID.randomUUID(), NOW.minus(Duration.ofHours(25)));
        prompt(UUID.randomUUID(), NOW.minus(Duration.ofHours(3)));
        ReviewPrompt done = prompt(UUID.randomUUID(), NOW.minus(Duration.ofHours(30)));
        done.markFulfilled();

        assertThat(service.sendDueReminders()).isEqualTo(1);
        assertThat(service.sendDueReminders()).isZero();

        verify(outbox, times(1)).publish(eq(ReviewReminderEvent.AGGREGATE_TYPE), eq(due.getId()),
                eq(ReviewReminderEvent.EVENT_TYPE), eq(new ReviewReminderEvent(due.getBookingId(), customer, worker)));
        assertThat(prompts.findById(due.getId()).orElseThrow().getRemindedAt()).isEqualTo(NOW);
    }

    @Test
    void thePublicPageShowsOnlyVisibleReviewsWithASummary() {
        review(5, false, NOW.minus(Duration.ofDays(1)));
        review(4, false, NOW.minus(Duration.ofDays(2)));
        review(1, true, NOW.minus(Duration.ofDays(3)));
        Review removed = review(2, false, NOW.minus(Duration.ofDays(4)));
        removed.deactivate();
        reviews.save(Review.providerReview(UUID.randomUUID(), worker, UUID.randomUUID(), 1, "rude", null, false, NOW));

        PublicReviews page = service.publicReviews(worker, 0, 10);

        assertThat(page.summary().count()).isEqualTo(2);
        assertThat(page.summary().average()).isNotNull();
        assertThat(page.summary().distribution()).containsEntry(5, 1L).containsEntry(4, 1L).containsEntry(1, 0L);
        assertThat(page.reviews()).extracting(ReviewExperienceService.ReviewView::overall).containsExactly(5, 4);
        assertThat(page.hasMore()).isFalse();
        assertThat(service.publicReviews(worker, 0, 1).hasMore()).isTrue();
        assertThat(service.publicReviews(UUID.randomUUID(), 0, 10).summary().count()).isZero();
    }

    @Test
    void theReviewedWorkerRepliesPublicly_noOneElseCan() {
        Review r = review(3, false, NOW.minus(Duration.ofDays(1)));

        var answered = service.reply(r.getId(), worker, "  Thanks! Sorry about the delay; fixed for next time.  ");

        assertThat(answered.reply()).isEqualTo("Thanks! Sorry about the delay; fixed for next time.");
        assertThat(answered.repliedAt()).isEqualTo(NOW);
        assertThat(service.receivedBy(worker)).extracting(ReviewExperienceService.ReviewView::reply)
                .containsExactly("Thanks! Sorry about the delay; fixed for next time.");

        assertThatThrownBy(() -> service.reply(r.getId(), UUID.randomUUID(), "Not mine"))
                .isInstanceOfSatisfying(ReviewException.class, e -> assertThat(e.getErrorCode()).isEqualTo("REVIEW_NOT_FOUND"));
        assertThatThrownBy(() -> service.reply(r.getId(), worker, " "))
                .isInstanceOf(ReviewException.class);
        assertThatThrownBy(() -> service.reply(r.getId(), worker, "x".repeat(501)))
                .isInstanceOf(ReviewException.class);
        Review flagged = review(1, true, NOW);
        assertThatThrownBy(() -> service.reply(flagged.getId(), worker, "Hidden review"))
                .isInstanceOf(ReviewException.class);
        verify(outbox, never()).publish(any(), any(), any(), any());
    }
}
