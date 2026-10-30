package com.jihajoor.rating.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.jihajoor.rating.config.RatingProperties;
import com.jihajoor.rating.domain.AggregateCalculator;
import com.jihajoor.rating.domain.Review;
import com.jihajoor.rating.domain.ReviewPrompt;
import com.jihajoor.rating.domain.ReviewPromptRepository;
import com.jihajoor.rating.domain.ReviewRepository;
import com.jihajoor.rating.domain.ReviewerRole;
import com.jihajoor.rating.event.ReviewReminderEvent;
import com.jihajoor.shared.outbox.OutboxEventPublisher;

/**
 * Turns reviews into something customers and workers use:
 * <ul>
 *   <li>the caller's review prompts, so the apps can ask "How was your job?" at the right time;</li>
 *   <li>one reminder to customers who have not rated a paid job after a day;</li>
 *   <li>a worker's public page of verified reviews, with a rating summary;</li>
 *   <li>the reviews a worker received, and their public reply to each.</li>
 * </ul>
 * Only reviews that are active and not held by the fraud checks are ever shown publicly, and no
 * reviewer is identified beyond "verified customer".
 */
@Service
public class ReviewExperienceService {

    private static final Logger log = LoggerFactory.getLogger(ReviewExperienceService.class);

    public static final int MAX_REPLY_LENGTH = 500;
    public static final int MAX_PAGE_SIZE = 20;
    private static final int REMINDER_BATCH = 200;

    private final ReviewRepository reviews;
    private final ReviewPromptRepository prompts;
    private final AggregateCalculator aggregateCalculator;
    private final OutboxEventPublisher outbox;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Duration reminderDelay;

    @org.springframework.beans.factory.annotation.Autowired
    public ReviewExperienceService(ReviewRepository reviews, ReviewPromptRepository prompts,
                                   RatingProperties properties, OutboxEventPublisher outbox,
                                   TransactionTemplate transactions,
                                   @Value("${jihajoor.rating.reminder-delay:PT24H}") Duration reminderDelay) {
        this(reviews, prompts, properties, outbox, transactions, reminderDelay, Clock.systemUTC());
    }

    /** Pins the clock for tests. */
    ReviewExperienceService(ReviewRepository reviews, ReviewPromptRepository prompts, RatingProperties properties,
                            OutboxEventPublisher outbox, TransactionTemplate transactions, Duration reminderDelay,
                            Clock clock) {
        this.reviews = reviews;
        this.prompts = prompts;
        this.aggregateCalculator = new AggregateCalculator(
                properties.getRecentWindow(), properties.getRecentWeight(), properties.getOlderWeight());
        this.outbox = outbox;
        this.transactions = transactions;
        this.clock = clock;
        this.reminderDelay = reminderDelay;
    }

    // ---- The caller's prompts --------------------------------------------------------------------

    /** The caller's review prompts of the last 30 days, each OPEN, REVIEWED or EXPIRED. */
    @Transactional(readOnly = true)
    public List<PromptView> promptsFor(UUID reviewerId) {
        Instant now = clock.instant();
        return prompts.findTop50ByReviewerIdAndCreatedAtAfterOrderByCreatedAtDesc(reviewerId,
                        now.minus(Duration.ofDays(30)))
                .stream()
                .map(p -> {
                    Integer rating = p.isFulfilled()
                            ? reviews.findFirstByBookingIdAndReviewerId(p.getBookingId(), reviewerId)
                                    .map(Review::getOverallRating).orElse(null)
                            : null;
                    String status = p.isFulfilled() ? "REVIEWED" : p.isOpenAt(now) ? "OPEN" : "EXPIRED";
                    return new PromptView(p.getBookingId(), p.getReviewerRole().name(), p.getRevieweeId(),
                            status, p.getExpiresAt(), rating);
                })
                .toList();
    }

    // ---- Reminders -------------------------------------------------------------------------------

    /**
     * Sends the one reminder each customer prompt gets, a day (by default) after the payment.
     * Each prompt is marked in the same transaction as its outbox event, so a reminder is sent
     * once even when two instances sweep at the same time (the second finds it reminded).
     *
     * @return how many reminders were queued
     */
    @Scheduled(fixedDelayString = "${jihajoor.rating.reminder-sweep-interval:PT15M}",
            initialDelayString = "${jihajoor.rating.reminder-sweep-initial-delay:PT2M}")
    public int sendDueReminders() {
        Instant now = clock.instant();
        List<ReviewPrompt> due = prompts.findDueReminders(ReviewerRole.CUSTOMER, now.minus(reminderDelay), now,
                PageRequest.of(0, REMINDER_BATCH));
        int sent = 0;
        for (ReviewPrompt candidate : due) {
            Boolean queued = transactions.execute(status -> {
                ReviewPrompt prompt = prompts.findById(candidate.getId()).orElse(null);
                if (prompt == null || prompt.isFulfilled() || prompt.getRemindedAt() != null || !prompt.isOpenAt(now)) {
                    return false;
                }
                prompt.markReminded(now);
                prompts.save(prompt);
                outbox.publish(ReviewReminderEvent.AGGREGATE_TYPE, prompt.getId(), ReviewReminderEvent.EVENT_TYPE,
                        new ReviewReminderEvent(prompt.getBookingId(), prompt.getReviewerId(), prompt.getRevieweeId()));
                return true;
            });
            if (Boolean.TRUE.equals(queued)) {
                sent++;
            }
        }
        if (sent > 0) {
            log.info("Queued {} review reminder(s)", sent);
        }
        return sent;
    }

    // ---- A worker's public page ------------------------------------------------------------------

    /** Rating summary and one page of a worker's visible customer reviews, newest first. */
    @Transactional(readOnly = true)
    public PublicReviews publicReviews(UUID workerId, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        List<Review> visible = reviews.findByRevieweeIdAndReviewerRole(workerId, ReviewerRole.CUSTOMER).stream()
                .filter(Review::isPubliclyVisible)
                .toList();
        List<ReviewView> items = reviews.findVisibleForWorker(workerId, PageRequest.of(safePage, safeSize)).stream()
                .map(ReviewExperienceService::view)
                .toList();
        return new PublicReviews(summary(visible), items, safePage, safeSize,
                (long) safePage * safeSize + items.size() < visible.size());
    }

    /** Every review a worker has received that is visible, newest first, with their replies. */
    @Transactional(readOnly = true)
    public List<ReviewView> receivedBy(UUID workerId) {
        return reviews.findVisibleForWorker(workerId, PageRequest.of(0, 100)).stream()
                .map(ReviewExperienceService::view)
                .toList();
    }

    /**
     * The reviewed worker's public answer to a customer's review; replacing an earlier answer is
     * allowed. Another worker's review, a hidden one or a worker's rating of a customer is not
     * found.
     */
    @Transactional
    public ReviewView reply(UUID reviewId, UUID workerId, String text) {
        String reply = text == null ? "" : text.strip();
        if (reply.isEmpty() || reply.length() > MAX_REPLY_LENGTH) {
            throw ReviewException.validation("Write a reply of 1 to " + MAX_REPLY_LENGTH + " characters.");
        }
        Review review = reviews.findById(reviewId)
                .filter(r -> r.getRevieweeId().equals(workerId) && r.isPubliclyVisible())
                .orElseThrow(() -> new ReviewException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "Review not found"));
        review.reply(reply, clock.instant());
        reviews.save(review);
        return view(review);
    }

    // ----------------------------------------------------------------------------------------------

    private RatingSummary summary(List<Review> visible) {
        if (visible.isEmpty()) {
            return new RatingSummary(null, 0, Map.of(5, 0L, 4, 0L, 3, 0L, 2, 0L, 1, 0L), null, null, null, null);
        }
        Map<Integer, Long> distribution = new java.util.TreeMap<>(java.util.Comparator.reverseOrder());
        for (int stars = 5; stars >= 1; stars--) {
            distribution.put(stars, 0L);
        }
        distribution.putAll(visible.stream().collect(
                Collectors.groupingBy(Review::getOverallRating, Collectors.counting())));
        BigDecimal average = aggregateCalculator.aggregate(visible, clock.instant());
        return new RatingSummary(average, visible.size(), distribution,
                mean(visible, r -> r.getQualityRating()), mean(visible, r -> r.getTimelinessRating()),
                mean(visible, r -> r.getBehaviorRating()), mean(visible, r -> r.getPricingTransparencyRating()));
    }

    private static BigDecimal mean(List<Review> list, java.util.function.Function<Review, Integer> field) {
        List<Integer> values = list.stream().map(field).filter(java.util.Objects::nonNull).toList();
        if (values.isEmpty()) {
            return null;
        }
        ToIntFunction<Integer> self = Integer::intValue;
        double avg = values.stream().mapToInt(self).average().orElse(0);
        return BigDecimal.valueOf(avg).setScale(1, RoundingMode.HALF_UP);
    }

    private static ReviewView view(Review r) {
        return new ReviewView(r.getId(), r.getOverallRating(), r.getQualityRating(), r.getTimelinessRating(),
                r.getBehaviorRating(), r.getPricingTransparencyRating(), r.getReviewText(), r.getSubmittedAt(),
                r.getProviderReply(), r.getRepliedAt());
    }

    /** One of the caller's prompts. {@code myRating} is set once they reviewed. */
    public record PromptView(UUID bookingId, String role, UUID revieweeId, String status, Instant expiresAt,
                             Integer myRating) {
    }

    /** A review as shown publicly: no reviewer identity, no IP, no booking. */
    public record ReviewView(UUID id, int overall, Integer quality, Integer timeliness, Integer behavior,
                             Integer pricingTransparency, String text, Instant submittedAt, String reply,
                             Instant repliedAt) {
    }

    /** Star distribution is keyed 5..1. Averages are null with no reviews. */
    public record RatingSummary(BigDecimal average, int count, Map<Integer, Long> distribution, BigDecimal quality,
                                BigDecimal timeliness, BigDecimal behavior, BigDecimal pricingTransparency) {
    }

    public record PublicReviews(RatingSummary summary, List<ReviewView> reviews, int page, int size,
                                boolean hasMore) {
    }
}
