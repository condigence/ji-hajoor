-- Two supply channels: independent workers register on their own; an agency (tenant) runs a team.
-- An agency can no longer attach a worker by mobile number alone: it sends an invitation and the
-- worker accepts it in the provider app (a Platform_Admin can still attach directly).
CREATE TABLE provider.tenant_invitation (
    id           uuid PRIMARY KEY,
    tenant_id    uuid        NOT NULL REFERENCES provider.tenant (id),
    provider_id  uuid        NOT NULL,
    invited_by   uuid        NOT NULL,
    status       varchar(16) NOT NULL
        CONSTRAINT tenant_invitation_status_check
            CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'CANCELLED')),
    created_at   timestamptz NOT NULL,
    responded_at timestamptz
);

-- At most one open invitation from an agency to a worker.
CREATE UNIQUE INDEX uq_tenant_invitation_pending
    ON provider.tenant_invitation (tenant_id, provider_id) WHERE status = 'PENDING';
CREATE INDEX idx_tenant_invitation_provider ON provider.tenant_invitation (provider_id, status);

-- The agency's share of the worker's net earning on its workers' jobs, set by a Platform_Admin.
-- 0 (the default) keeps today's behaviour: the worker keeps the whole net.
ALTER TABLE provider.tenant ADD COLUMN agency_share_percent numeric(5, 2) NOT NULL DEFAULT 0
    CONSTRAINT tenant_agency_share_check CHECK (agency_share_percent BETWEEN 0 AND 50);

-- The worker's ledger itemises the agency's cut as its own deduction line.
ALTER TABLE provider.provider_earning DROP CONSTRAINT provider_earning_type_check;
ALTER TABLE provider.provider_earning ADD CONSTRAINT provider_earning_type_check
    CHECK (type IN ('JOB_CREDIT', 'PLATFORM_FEE_DEDUCTION', 'PENALTY_DEDUCTION', 'AGENCY_SHARE_DEDUCTION'));

-- The agency's side of the split: one row per booking, so a re-sent credit cannot pay it twice.
CREATE TABLE provider.agency_earning (
    id                uuid PRIMARY KEY,
    tenant_id         uuid          NOT NULL REFERENCES provider.tenant (id),
    provider_id       uuid          NOT NULL,
    booking_id        uuid          NOT NULL,
    booking_reference varchar(64),
    worker_net        numeric(12, 2) NOT NULL,
    share_percent     numeric(5, 2) NOT NULL,
    amount            numeric(12, 2) NOT NULL,
    credited_at       timestamptz   NOT NULL
);

CREATE UNIQUE INDEX uq_agency_earning_booking ON provider.agency_earning (booking_id);
CREATE INDEX idx_agency_earning_tenant ON provider.agency_earning (tenant_id, credited_at DESC);
