-- Customer reviews now reach the provider's rating (Requirements 4.7, 15.4). The Rating & Review
-- Service owns the reviews, recomputes a provider's weighted aggregate whenever the set of reviews
-- that count changes (submission, Admin approval of a flagged review, Admin removal), and sets it
-- here through PUT /internal/providers/{providerId}/rating.
--
-- aggregate_rating already exists (V1); it stays 0 for a provider nobody has reviewed yet.
--
-- rating_count: how many customer reviews the aggregate is over, for display next to the rating.
--
-- rating_sequence: the Rating & Review Service's per-provider change number for the aggregate it
-- last applied. It only ever grows there, so an older aggregate that arrives late (two pushes for
-- the same provider racing, or a retry overtaking a newer push) is recognised and ignored instead
-- of overwriting the newer one. 0 = never set by the Rating & Review Service.
ALTER TABLE provider.provider_profile ADD COLUMN rating_count integer NOT NULL DEFAULT 0;
ALTER TABLE provider.provider_profile ADD COLUMN rating_sequence bigint NOT NULL DEFAULT 0;
