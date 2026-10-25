-- Provider rating propagation (Requirements 4.7, 15.4). This service owns the reviews and computes a
-- provider's weighted aggregate; the Provider Service stores it (PUT /internal/providers/{id}/rating),
-- because dispatch, the provider's profile and the Admin lists read it there.
--
-- One row per provider whose counted customer reviews have ever changed. Every change (a review
-- submitted, a flagged review approved, a review removed) bumps change_sequence in the same
-- transaction as the change; once the Provider Service has accepted the aggregate computed at that
-- sequence, pushed_sequence catches up. change_sequence > pushed_sequence therefore means "the
-- Provider Service may be showing an out-of-date rating", durably: the push is attempted right
-- after the commit, and ProviderRatingSweeper re-sends whatever is still owed after that failed or
-- the process died in between.
--
-- change_sequence also travels with every push. The Provider Service ignores a push numbered below
-- the one it holds, so a late, older aggregate never overwrites a newer one.
CREATE TABLE rating.provider_rating_sync (
    provider_id uuid NOT NULL,
    change_sequence bigint NOT NULL,
    pushed_sequence bigint NOT NULL,
    changed_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT provider_rating_sync_pkey PRIMARY KEY (provider_id)
);

-- The sweeper's query: owed rows, oldest change first.
CREATE INDEX ix_provider_rating_sync_owed ON rating.provider_rating_sync (changed_at)
    WHERE change_sequence > pushed_sequence;

-- Ratings were never propagated before this version, so every provider reviewed so far starts out
-- owed: the first sweep after the upgrade sets their aggregates in the Provider Service.
INSERT INTO rating.provider_rating_sync (provider_id, change_sequence, pushed_sequence, changed_at)
SELECT DISTINCT reviewee_id, 1, 0, now()
FROM rating.review
WHERE reviewer_role = 'CUSTOMER';
