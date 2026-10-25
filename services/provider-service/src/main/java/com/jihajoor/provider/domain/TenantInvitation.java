package com.jihajoor.provider.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An agency's invitation to a worker to join its team. A worker joins an agency only by accepting
 * one, so an agency cannot take over somebody's account by knowing their mobile number.
 */
@Entity
@Table(name = "tenant_invitation")
public class TenantInvitation {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "provider_id", nullable = false, updatable = false)
    private UUID providerId;

    @Column(name = "invited_by", nullable = false, updatable = false)
    private UUID invitedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private InvitationStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    protected TenantInvitation() {
        // JPA
    }

    public static TenantInvitation pending(UUID tenantId, UUID providerId, UUID invitedBy) {
        TenantInvitation invitation = new TenantInvitation();
        invitation.id = UUID.randomUUID();
        invitation.tenantId = tenantId;
        invitation.providerId = providerId;
        invitation.invitedBy = invitedBy;
        invitation.status = InvitationStatus.PENDING;
        invitation.createdAt = Instant.now();
        return invitation;
    }

    public boolean isPending() {
        return status == InvitationStatus.PENDING;
    }

    /** Closes a pending invitation with {@code outcome}; a closed one is left as it is. */
    public void close(InvitationStatus outcome) {
        if (isPending() && outcome != InvitationStatus.PENDING) {
            this.status = outcome;
            this.respondedAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getProviderId() {
        return providerId;
    }

    public UUID getInvitedBy() {
        return invitedBy;
    }

    public InvitationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getRespondedAt() {
        return respondedAt;
    }
}
