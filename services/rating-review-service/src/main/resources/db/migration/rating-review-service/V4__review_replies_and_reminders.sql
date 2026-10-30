-- Workers answer the reviews they receive (one reply, which they can edit), and a customer who
-- has not rated a job a day after paying gets one reminder.
ALTER TABLE rating.review ADD COLUMN IF NOT EXISTS provider_reply varchar(500);
ALTER TABLE rating.review ADD COLUMN IF NOT EXISTS replied_at timestamp(6) with time zone;

ALTER TABLE rating.review_prompt ADD COLUMN IF NOT EXISTS reminded_at timestamp(6) with time zone;

-- A worker's public page: visible customer reviews, newest first.
CREATE INDEX IF NOT EXISTS idx_review_public_page
    ON rating.review (reviewee_id, submitted_at DESC)
    WHERE reviewer_role = 'CUSTOMER' AND is_active AND NOT is_flagged;

-- "My review prompts" and the reminder sweep.
CREATE INDEX IF NOT EXISTS idx_review_prompt_reviewer
    ON rating.review_prompt (reviewer_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_review_prompt_reminder_due
    ON rating.review_prompt (created_at)
    WHERE NOT fulfilled AND reminded_at IS NULL;
