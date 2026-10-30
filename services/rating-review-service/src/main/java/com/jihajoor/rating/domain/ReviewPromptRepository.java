package com.jihajoor.rating.domain;

import java.util.Optional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link ReviewPrompt}. Prompts are looked up by booking + reviewer role when a
 * review is submitted (Requirement 15.2) and by payment to short-circuit duplicate
 * {@code PaymentCompleted} deliveries (Requirement 15.1).
 */
public interface ReviewPromptRepository extends JpaRepository<ReviewPrompt, UUID> {

    Optional<ReviewPrompt> findByBookingIdAndReviewerRole(UUID bookingId, ReviewerRole reviewerRole);

    boolean existsByPaymentId(UUID paymentId);

    /** The caller's recent prompts (open, done or expired), newest first. */
    List<ReviewPrompt> findTop50ByReviewerIdAndCreatedAtAfterOrderByCreatedAtDesc(UUID reviewerId, Instant since);

    /** Customer prompts still open, never reminded, opened before {@code cutoff}. */
    @Query("select p from ReviewPrompt p where p.fulfilled = false and p.remindedAt is null"
            + " and p.reviewerRole = :role and p.createdAt < :cutoff and p.expiresAt > :now"
            + " order by p.createdAt")
    List<ReviewPrompt> findDueReminders(@Param("role") ReviewerRole role, @Param("cutoff") Instant cutoff,
                                        @Param("now") Instant now, Pageable page);
}
