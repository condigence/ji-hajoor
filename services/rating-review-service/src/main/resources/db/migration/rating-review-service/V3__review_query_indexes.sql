-- Indexes for the queries every review submission runs. V1 created primary keys only.

-- The provider's weighted aggregate, recomputed after every submit, approval and removal
-- (ReviewRepository.findByRevieweeIdAndReviewerRole).
CREATE INDEX IF NOT EXISTS idx_review_reviewee_role
    ON rating.review (reviewee_id, reviewer_role);

-- Same-IP burst detection (findBySourceIpAndSubmittedAtGreaterThanEqual).
CREATE INDEX IF NOT EXISTS idx_review_source_ip_submitted
    ON rating.review (source_ip, submitted_at)
    WHERE source_ip IS NOT NULL;

-- One review per prompt (existsByBookingIdAndReviewerId).
CREATE INDEX IF NOT EXISTS idx_review_booking_reviewer
    ON rating.review (booking_id, reviewer_id);

-- Prompt lookups by booking (findByBookingIdAndReviewerRole) and by payment (existsByPaymentId).
CREATE INDEX IF NOT EXISTS idx_review_prompt_booking_role
    ON rating.review_prompt (booking_id, reviewer_role);
CREATE INDEX IF NOT EXISTS idx_review_prompt_payment
    ON rating.review_prompt (payment_id);
