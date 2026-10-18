package com.jihajoor.complaint.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

/**
 * Persistence for {@link Complaint}. Beyond CRUD, exposes the queries backing the SLA-breach
 * escalation sweep (Requirement 16.4) and the complaint statistics aggregation (Requirement 16.9),
 * which is computed with {@code GROUP BY}/aggregate queries rather than by loading every complaint.
 */
public interface ComplaintRepository extends JpaRepository<Complaint, UUID> {

    /**
     * Non-terminal (not RESOLVED/CLOSED), not-yet-escalated complaints whose SLA deadline is
     * on/before {@code asOf}. Backs the periodic escalation sweep (Requirement 16.4).
     *
     * <p>The terminal statuses and {@code escalated = false} are literals, not parameters, so the
     * partial index {@code idx_complaint_sla_sweep} (migration V4), whose predicate is exactly
     * that, serves the query under any plan, including a generic prepared-statement plan.
     */
    @Query("""
            select c from Complaint c
            where c.escalated = false
              and c.status not in (com.jihajoor.complaint.domain.ComplaintStatus.RESOLVED,
                                   com.jihajoor.complaint.domain.ComplaintStatus.CLOSED)
              and c.slaDeadline <= :asOf
            """)
    List<Complaint> findSlaBreachCandidates(@Param("asOf") Instant asOf);

    /**
     * Loads a complaint holding a row lock until the surrounding transaction ends. Serialises
     * concurrent refund approvals of the same complaint (Requirement 16.5).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Complaint c where c.id = :id")
    Optional<Complaint> findByIdForUpdate(@Param("id") UUID id);

    /**
     * The Admin Portal complaint list (Requirement 19.2): complaints in any of {@code statuses}
     * whose description, or complaint, booking or customer id, contains {@code pattern} (a
     * lower-case {@code LIKE} pattern with {@code \} escaping literal wildcards; {@code "%"}
     * matches everything), newest first, bounded by {@code page}. The ids are UUIDs, so the
     * cast-and-lower comparison is how a partial id typed into the portal's search box finds its
     * complaint.
     */
    @Query("""
            select c from Complaint c
            where c.status in :statuses
              and (lower(c.description) like :pattern escape '\\'
                   or lower(cast(c.bookingId as string)) like :pattern escape '\\'
                   or lower(cast(c.customerId as string)) like :pattern escape '\\'
                   or lower(cast(c.id as string)) like :pattern escape '\\')
            order by c.createdAt desc
            """)
    List<Complaint> searchForAdmin(@Param("statuses") Collection<ComplaintStatus> statuses,
                                   @Param("pattern") String pattern,
                                   Pageable page);

    /**
     * Complaint count per category (Requirement 16.9), aggregated in the database. Categories with
     * no complaint are absent; the stats calculator fills them in with zero.
     */
    @Query("""
            select new com.jihajoor.complaint.domain.ComplaintCategoryCount(c.category, count(c))
            from Complaint c
            group by c.category
            """)
    List<ComplaintCategoryCount> countByCategory();

    /**
     * How many complaints are resolved (RESOLVED/CLOSED with a resolution time) and the sum of
     * their creation-to-resolution times in whole milliseconds (Requirement 16.9), aggregated in the
     * database.
     *
     * <p>Each time is taken to the nanosecond and floored to the millisecond before summing, which
     * is what {@code Duration.toMillis()} does per complaint in
     * {@link com.jihajoor.complaint.service.ComplaintStatsCalculator#aggregate}, so both give the same
     * average. ({@code by second} is not used: H2 truncates it to whole seconds per row while
     * PostgreSQL keeps the fraction.)
     */
    @Query("""
            select new com.jihajoor.complaint.domain.ComplaintResolutionTotals(
                       count(c),
                       sum(floor(((c.resolvedAt - c.createdAt) by nanosecond) / 1000000)))
            from Complaint c
            where c.status in (com.jihajoor.complaint.domain.ComplaintStatus.RESOLVED,
                               com.jihajoor.complaint.domain.ComplaintStatus.CLOSED)
              and c.resolvedAt is not null
            """)
    ComplaintResolutionTotals resolutionTotals();
}
