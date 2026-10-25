package com.jihajoor.rating.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence for {@link ProviderRatingSyncState}: the per-provider record of which aggregate
 * rating the Provider Service has accepted (Requirements 4.7, 15.4).
 *
 * <p>A plain {@link Repository} exposing only these statements, so no generic {@code save} can
 * write a stale copy of a row over a concurrent bump.
 */
public interface ProviderRatingSyncRepository extends Repository<ProviderRatingSyncState, UUID> {

    /**
     * Records a change to the provider's counted reviews: inserts the row at sequence 1, or bumps
     * an existing row's sequence. One atomic statement, so two transactions changing the same
     * provider's reviews at once each get their own sequence and neither fails on the insert: the
     * second waits on the first's row lock until it commits. Runs inside the caller's transaction,
     * so the bump commits (or rolls back) with the review change itself.
     *
     * <p>PostgreSQL {@code ON CONFLICT}, hence native; the schema is named because native SQL does
     * not get Hibernate's {@code default_schema}.
     */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            insert into rating.provider_rating_sync as s
                (provider_id, change_sequence, pushed_sequence, changed_at)
            values (:providerId, 1, 0, :changedAt)
            on conflict (provider_id) do update
                set change_sequence = s.change_sequence + 1,
                    changed_at = excluded.changed_at
            """)
    int markChanged(@Param("providerId") UUID providerId, @Param("changedAt") Instant changedAt);

    Optional<ProviderRatingSyncState> findById(UUID providerId);

    /**
     * Providers whose latest change is still owed to the Provider Service and is at least as old as
     * {@code cutoff}, oldest first. Drives {@code ProviderRatingSweeper}.
     */
    @Query("""
            select s from ProviderRatingSyncState s
            where s.changeSequence > s.pushedSequence and s.changedAt <= :cutoff
            order by s.changedAt asc
            """)
    List<ProviderRatingSyncState> findOwed(@Param("cutoff") Instant cutoff, Pageable page);

    /**
     * Records that the Provider Service holds the aggregate computed at {@code sequence} (or a newer
     * one). Never moves backwards, so a slow push finishing after a newer one cannot re-open it.
     *
     * @return 1 when recorded, 0 when an equal or newer push was already recorded
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update ProviderRatingSyncState s set s.pushedSequence = :sequence
            where s.providerId = :providerId and s.pushedSequence < :sequence
            """)
    int markPushed(@Param("providerId") UUID providerId, @Param("sequence") long sequence);
}
