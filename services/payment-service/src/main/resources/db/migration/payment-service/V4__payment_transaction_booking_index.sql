-- The internal refund endpoint (POST /internal/payments/refunds, called by the Complaint Service)
-- identifies the payment to refund by its booking. Not unique: a booking keeps one row per payment
-- attempt (a FAILED charge opens the next attempt under its own row).
CREATE INDEX IF NOT EXISTS ix_payment_transaction_booking
    ON payment.payment_transaction USING btree (booking_id);
