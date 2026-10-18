-- Indexes for the invoice read paths. V1 only has the primary key and the unique constraints on
-- payment_id and invoice_number, so every one of these queries scanned invoice.invoice in full.
--
-- * Customer history (GET /invoices/customers/{id}): one customer's invoices since the retention
--   floor, newest first, paged. With generated_at in the key, in the query's direction, the page is
--   read straight off the index instead of sorting every invoice the customer has.
-- * Provider monthly statement (GET /invoices/providers/{id}/statements/{y}/{m}): one provider's
--   invoices in a half-open [from, to) generated_at window.
-- * Invoice for a booking (GET /invoices/bookings/{bookingId}): the customer app looks this up
--   once a booking is paid.
--
-- A plain CREATE INDEX briefly blocks writes to invoice.invoice while it builds. That is fine at
-- the table's current size; CONCURRENTLY would need this migration to run outside Flyway's
-- transaction. IF NOT EXISTS keeps the migration safe on a database where an index was already
-- created by hand.
CREATE INDEX IF NOT EXISTS idx_invoice_customer_generated
    ON invoice.invoice (customer_id, generated_at DESC);

CREATE INDEX IF NOT EXISTS idx_invoice_provider_generated
    ON invoice.invoice (provider_id, generated_at DESC);

CREATE INDEX IF NOT EXISTS idx_invoice_booking
    ON invoice.invoice (booking_id, generated_at DESC);
