-- Indexes for the complaint table's hot queries. Until now it had only its primary key, so each of
-- these read the whole table.
--
-- 1. The SLA escalation sweep (ComplaintRepository.findSlaBreachCandidates, every minute by
--    default, Requirement 16.4):
--      WHERE escalated = false AND status NOT IN ('RESOLVED', 'CLOSED') AND sla_deadline <= ?
--    Only complaints still being worked and not yet escalated are indexed, which keeps the index
--    small as resolved complaints pile up; the query writes that predicate with literals, so it
--    implies the index predicate under any plan, a generic prepared-statement plan included.
--    sla_deadline serves the range.
--
-- 2. The Admin Portal complaint list (ComplaintRepository.searchForAdmin, Requirement 19.2):
--    newest first, capped at 200 rows. With created_at in the key, in the query's direction, the
--    unfiltered list (and a search that matches often) is read off the index and stops at the cap
--    instead of sorting every complaint first.
--
-- No per-customer complaint query exists in this service (a customer's complaints are not listed
-- here), so there is no customer_id index to add. complaint_refund.complaint_id is already
-- indexed by its unique constraint (uk_complaint_refund_complaint).
--
-- A plain CREATE INDEX briefly blocks writes to complaint.complaint while it builds, which is fine
-- at the table's size; CONCURRENTLY would need this migration to run outside Flyway's
-- transaction. IF NOT EXISTS keeps it safe where an index was already created by hand.
-- Additive: no data changes.
CREATE INDEX IF NOT EXISTS idx_complaint_sla_sweep ON complaint.complaint (sla_deadline)
    WHERE escalated = false AND status NOT IN ('RESOLVED', 'CLOSED');

CREATE INDEX IF NOT EXISTS idx_complaint_created ON complaint.complaint (created_at DESC);
