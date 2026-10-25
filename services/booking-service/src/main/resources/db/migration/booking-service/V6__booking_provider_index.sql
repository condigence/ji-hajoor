-- A provider's jobs in given states, soonest first (BookingRepository
-- findByProviderIdAndStatusInOrderByScheduledAtAsc, behind GET /internal/providers/{id}/jobs).
-- The provider app polls it every 15 s per signed-in provider, and booking.booking had no index
-- on provider_id, so every poll scanned the whole table.
--
-- Plain CREATE INDEX (inside Flyway's transaction), as in V2; IF NOT EXISTS keeps it safe where
-- the index was created by hand.
CREATE INDEX IF NOT EXISTS idx_booking_provider_status
    ON booking.booking (provider_id, status, scheduled_at);
