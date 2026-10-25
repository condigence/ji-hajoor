-- The Admin Portal's dispatch rules (Requirements 8.2, 8.4, 8.5, 8.8, 19.5): the five matching
-- weights, the search radius and its expansion, the offer timeout, the wave size and the
-- agency-fallback time. See com.jihajoor.dispatch.adapter.JpaDispatchSettingsStore.
--
-- Before this table the rules lived in each replica's memory, so an Admin save was lost on restart
-- and reached only the replica that served it. Every replica now reads this row (through a short
-- cache, jihajoor.dispatch.settings-cache-ttl) before each search.
--
-- One row, not one row per setting: the rules are read and written as one form, the weights are
-- only valid together (they must sum to exactly 1), and typed columns let the database enforce
-- that and let Hibernate validate the mapping. The id is pinned to 1 so a second row cannot exist.
--
-- No row is inserted here. Until an Admin first saves the rules, every replica uses the
-- jihajoor.dispatch.* configuration (whose defaults are the platform defaults), so an environment
-- can still choose its own starting values; from the first save on, this row wins.
--
-- The weights are unconstrained numeric so the sum is checked on exactly the decimals stored, with
-- no rounding to a fixed scale. The other ranges (cycles, timeout, wave size, fallback minutes) are
-- enforced by DispatchSettingsService, which owns them and can change them without a migration.
--
-- Mirrors com.jihajoor.dispatch.domain.DispatchSettingsRow exactly (ddl-auto=validate checks it).

CREATE TABLE IF NOT EXISTS dispatch.dispatch_settings (
    id                      integer                     NOT NULL,
    distance_weight         numeric                     NOT NULL,
    availability_weight     numeric                     NOT NULL,
    rating_weight           numeric                     NOT NULL,
    skill_weight            numeric                     NOT NULL,
    performance_weight      numeric                     NOT NULL,
    initial_radius_km       double precision            NOT NULL,
    radius_increment_km     double precision            NOT NULL,
    max_expansion_cycles    integer                     NOT NULL,
    offer_timeout_seconds   bigint                      NOT NULL,
    offer_batch_size        integer                     NOT NULL,
    agency_fallback_minutes bigint                      NOT NULL,
    -- Bumped on every save (JPA @Version); a cheap "has it changed" marker for operators.
    version                 bigint                      NOT NULL,
    -- Audit of the last save: when, and by whom (the Admin's user id, the JWT subject).
    updated_at              timestamp(6) with time zone NOT NULL,
    updated_by              varchar(255),
    PRIMARY KEY (id),
    CONSTRAINT dispatch_settings_single_row CHECK (id = 1),
    CONSTRAINT dispatch_settings_weights_in_range CHECK (
        distance_weight BETWEEN 0 AND 1
        AND availability_weight BETWEEN 0 AND 1
        AND rating_weight BETWEEN 0 AND 1
        AND skill_weight BETWEEN 0 AND 1
        AND performance_weight BETWEEN 0 AND 1),
    CONSTRAINT dispatch_settings_weights_sum_to_one CHECK (
        distance_weight + availability_weight + rating_weight + skill_weight + performance_weight = 1)
);
