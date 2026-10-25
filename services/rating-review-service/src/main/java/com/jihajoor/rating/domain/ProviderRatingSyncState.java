package com.jihajoor.rating.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Whether the Provider Service holds a provider's current aggregate rating (Requirements 4.7,
 * 15.4): one row per provider whose counted customer reviews have changed.
 *
 * <p>{@link #changeSequence} is bumped in the same transaction as every change to the provider's
 * counted reviews; {@link #pushedSequence} records the highest sequence whose aggregate the Provider
 * Service accepted. While {@code changeSequence > pushedSequence} the provider's rating is
 * <em>owed</em>, and {@code ProviderRatingSweeper} keeps re-sending it. The sequence is sent with
 * every push so the Provider Service can ignore an older aggregate that arrives after a newer one.
 *
 * <p>Read-only here: rows are written only by the statements in {@link ProviderRatingSyncRepository}
 * (an atomic upsert and a conditional update), so two concurrent changes cannot lose a bump.
 */
@Entity
@Table(name = "provider_rating_sync")
public class ProviderRatingSyncState {

    @Id
    @Column(name = "provider_id", nullable = false, updatable = false)
    private UUID providerId;

    @Column(name = "change_sequence", nullable = false)
    private long changeSequence;

    @Column(name = "pushed_sequence", nullable = false)
    private long pushedSequence;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    protected ProviderRatingSyncState() {
        // JPA
    }

    /** Visible for test doubles of {@link ProviderRatingSyncRepository}. */
    public ProviderRatingSyncState(UUID providerId, long changeSequence, long pushedSequence,
                                   Instant changedAt) {
        this.providerId = providerId;
        this.changeSequence = changeSequence;
        this.pushedSequence = pushedSequence;
        this.changedAt = changedAt;
    }

    /** Whether a change has not yet been accepted by the Provider Service. */
    public boolean isOwed() {
        return changeSequence > pushedSequence;
    }

    public UUID getProviderId() {
        return providerId;
    }

    public long getChangeSequence() {
        return changeSequence;
    }

    public long getPushedSequence() {
        return pushedSequence;
    }

    public Instant getChangedAt() {
        return changedAt;
    }
}
