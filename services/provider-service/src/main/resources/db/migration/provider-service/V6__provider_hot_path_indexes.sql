-- Indexes for the provider-service queries that run on every dispatch, profile read and
-- earnings screen. V1 (a pg_dump of Hibernate's schema) created primary keys only, so each of
-- these was a sequential scan.

-- Dispatch candidates (ProviderProfileRepository.findDispatchCandidates): joins skill tags
-- on provider_id and filters lower(tag) IN (...).
CREATE INDEX IF NOT EXISTS idx_provider_skill_tag_tag
    ON provider.provider_skill_tag (lower(tag), provider_id);
CREATE INDEX IF NOT EXISTS idx_provider_skill_tag_provider
    ON provider.provider_skill_tag (provider_id);

-- Its bounding box on the base location, for providers not under review.
CREATE INDEX IF NOT EXISTS idx_provider_profile_base_location
    ON provider.provider_profile (base_latitude, base_longitude)
    WHERE under_review = false AND base_latitude IS NOT NULL AND base_longitude IS NOT NULL;

-- The profile's element collections, loaded by provider_id on every profile fetch.
CREATE INDEX IF NOT EXISTS idx_availability_slot_provider
    ON provider.availability_slot (provider_id);
CREATE INDEX IF NOT EXISTS idx_provider_category_selection_provider
    ON provider.provider_category_selection (provider_id);
CREATE INDEX IF NOT EXISTS idx_provider_subcategory_selection_selection
    ON provider.provider_subcategory_selection (selection_id);

-- Earnings history (newest first, paged) and the summary's "since" window.
CREATE INDEX IF NOT EXISTS idx_provider_earning_provider_credited
    ON provider.provider_earning (provider_id, credited_at DESC);

-- Settlement history, newest first.
CREATE INDEX IF NOT EXISTS idx_settlement_provider_requested
    ON provider.settlement (provider_id, requested_at DESC);
