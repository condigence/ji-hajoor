-- When the latest call to the Payment Service for a complaint refund started. A PENDING refund
-- whose attempt is older than jihajoor.complaint.refund-attempt-lease is presumed lost and may be
-- re-sent (under the same idempotency key) by a same-key retry of the approval.
-- Nullable: refunds recorded before this column existed read it as their requested_at.
ALTER TABLE complaint.complaint_refund ADD COLUMN last_attempt_at timestamp(6) with time zone;
