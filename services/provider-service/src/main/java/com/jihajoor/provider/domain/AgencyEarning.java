package com.jihajoor.provider.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The agency's share of one of its workers' jobs: {@code sharePercent} of the worker's net
 * earning (gross less the platform fee), recorded once per booking.
 */
@Entity
@Table(name = "agency_earning")
public class AgencyEarning {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "provider_id", nullable = false, updatable = false)
    private UUID providerId;

    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    @Column(name = "booking_reference", length = 64)
    private String bookingReference;

    @Column(name = "worker_net", nullable = false, precision = 12, scale = 2)
    private BigDecimal workerNet;

    @Column(name = "share_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal sharePercent;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "credited_at", nullable = false, updatable = false)
    private Instant creditedAt;

    protected AgencyEarning() {
        // JPA
    }

    public AgencyEarning(UUID tenantId, UUID providerId, UUID bookingId, String bookingReference,
                         BigDecimal workerNet, BigDecimal sharePercent, BigDecimal amount) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.providerId = providerId;
        this.bookingId = bookingId;
        this.bookingReference = bookingReference;
        this.workerNet = workerNet;
        this.sharePercent = sharePercent;
        this.amount = amount;
        this.creditedAt = Instant.now();
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

    public UUID getBookingId() {
        return bookingId;
    }

    public String getBookingReference() {
        return bookingReference;
    }

    public BigDecimal getWorkerNet() {
        return workerNet;
    }

    public BigDecimal getSharePercent() {
        return sharePercent;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Instant getCreditedAt() {
        return creditedAt;
    }
}
