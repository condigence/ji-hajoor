package com.jihajoor.complaint.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import com.jihajoor.complaint.domain.Complaint;
import com.jihajoor.complaint.domain.ComplaintCategory;
import com.jihajoor.complaint.domain.ComplaintCategoryCount;
import com.jihajoor.complaint.domain.ComplaintRepository;
import com.jihajoor.complaint.domain.ComplaintResolutionTotals;
import com.jihajoor.complaint.domain.ComplaintStatus;
import com.jihajoor.complaint.domain.ServicePriority;

/**
 * The database-side complaint statistics (Requirement 16.9) and the SLA sweep query (16.4) against
 * a real database: the {@code GROUP BY} category count and the resolution totals must give exactly
 * the snapshot the in-memory {@link ComplaintStatsCalculator#aggregate} gives over the same rows.
 * H2 in PostgreSQL mode; PostgreSQL itself is not exercised (no Docker in this build).
 */
@DataJpaTest
@ContextConfiguration(classes = ComplaintStatsJpaTest.JpaConfig.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true",
        "spring.datasource.url=jdbc:h2:mem:complaint_stats;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ComplaintStatsJpaTest {

    private static final Instant BASE = Instant.parse("2024-06-01T12:00:00.123456Z");

    @Autowired
    private ComplaintRepository complaints;

    private final ComplaintStatsCalculator calculator = new ComplaintStatsCalculator();

    @BeforeEach
    void clean() {
        complaints.deleteAll();
    }

    private Complaint complaint(ComplaintCategory category, Instant createdAt, Instant slaDeadline) {
        return complaints.save(Complaint.open(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), category, ServicePriority.STANDARD,
                "The new tap leaks.", createdAt, slaDeadline));
    }

    private Complaint complaint(ComplaintCategory category) {
        return complaint(category, BASE, BASE.plus(Duration.ofHours(72)));
    }

    @Test
    void emptyTableGivesZeroedStatsWithEveryCategory() {
        ComplaintStats stats = calculator.fromTotals(complaints.countByCategory(),
                complaints.resolutionTotals());

        assertThat(stats).isEqualTo(calculator.aggregate(complaints.findAll()));
        assertThat(stats.totalComplaints()).isZero();
        assertThat(stats.averageResolutionTime()).isEqualTo(Duration.ZERO);
        assertThat(stats.categoryCounts()).hasSize(ComplaintCategory.values().length);
    }

    @Test
    void databaseAggregatesMatchTheInMemoryAggregation() {
        Complaint resolvedFast = complaint(ComplaintCategory.POOR_QUALITY);
        resolvedFast.resolve(BASE.plus(Duration.ofMinutes(90).plusNanos(250_500_000)));
        complaints.save(resolvedFast);
        Complaint closedSlow = complaint(ComplaintCategory.POOR_QUALITY);
        closedSlow.close(BASE.plus(Duration.ofHours(30).plusNanos(1_000)));
        complaints.save(closedSlow);
        Complaint resolvedOddMillis = complaint(ComplaintCategory.DAMAGE,
                BASE.plusNanos(999_000), BASE.plus(Duration.ofHours(72)));
        resolvedOddMillis.resolve(BASE.plus(Duration.ofSeconds(7)));
        complaints.save(resolvedOddMillis);
        complaint(ComplaintCategory.DAMAGE);
        Complaint disputed = complaint(ComplaintCategory.OVERCHARGING);
        disputed.changeStatus(ComplaintStatus.DISPUTED);
        complaints.save(disputed);
        complaints.flush();

        ComplaintStats fromDatabase = calculator.fromTotals(complaints.countByCategory(),
                complaints.resolutionTotals());
        ComplaintStats reference = calculator.aggregate(complaints.findAll());

        assertThat(fromDatabase).isEqualTo(reference);
        assertThat(fromDatabase.totalComplaints()).isEqualTo(5);
        assertThat(fromDatabase.resolvedComplaints()).isEqualTo(3);
        assertThat(fromDatabase.categoryCounts().get(ComplaintCategory.POOR_QUALITY)).isEqualTo(2);
        assertThat(fromDatabase.categoryCounts().get(ComplaintCategory.DAMAGE)).isEqualTo(2);
        assertThat(fromDatabase.categoryCounts().get(ComplaintCategory.OVERCHARGING)).isEqualTo(1);
        assertThat(fromDatabase.categoryCounts().get(ComplaintCategory.LATE_ARRIVAL)).isZero();
    }

    /**
     * Per-complaint times are floored to the millisecond before averaging, as
     * {@code Duration.toMillis()} does in the in-memory path: 1.9 ms and 0.9 ms average to 0 ms
     * (1 + 0, halved), not to the 1.4 ms their exact sum would give.
     */
    @Test
    void resolutionTimesAreFlooredToTheMillisecondPerComplaintBeforeAveraging() {
        Complaint a = complaint(ComplaintCategory.DAMAGE);
        a.resolve(BASE.plusNanos(1_900_000));
        complaints.save(a);
        Complaint b = complaint(ComplaintCategory.DAMAGE);
        b.resolve(BASE.plusNanos(900_000));
        complaints.save(b);
        complaints.flush();

        ComplaintStats fromDatabase = calculator.fromTotals(complaints.countByCategory(),
                complaints.resolutionTotals());

        assertThat(fromDatabase).isEqualTo(calculator.aggregate(complaints.findAll()));
        assertThat(fromDatabase.averageResolutionTime()).isEqualTo(Duration.ZERO);
    }

    @Test
    void groupByReturnsOnlyCategoriesThatHaveComplaints() {
        complaint(ComplaintCategory.DAMAGE);
        complaint(ComplaintCategory.DAMAGE);
        complaints.flush();

        assertThat(complaints.countByCategory())
                .containsExactly(new ComplaintCategoryCount(ComplaintCategory.DAMAGE, 2L));
        ComplaintResolutionTotals none = complaints.resolutionTotals();
        assertThat(none.resolved()).isZero();
        assertThat(none.resolutionMillis()).isNull();
    }

    @Test
    void slaSweepQueryFindsOnlyBreachedOpenUnescalatedComplaints() {
        Instant now = BASE.plus(Duration.ofHours(100));
        Complaint breached = complaint(ComplaintCategory.DAMAGE, BASE, now.minusSeconds(1));
        Complaint dueNow = complaint(ComplaintCategory.DAMAGE, BASE, now);
        Complaint breachedRefundFailed = complaint(ComplaintCategory.DAMAGE, BASE, now.minusSeconds(1));
        breachedRefundFailed.markRefundFailed();
        complaints.save(breachedRefundFailed);
        complaint(ComplaintCategory.DAMAGE, BASE, now.plusSeconds(1));
        Complaint escalated = complaint(ComplaintCategory.DAMAGE, BASE, now.minusSeconds(1));
        escalated.escalateTo(UUID.randomUUID());
        complaints.save(escalated);
        Complaint resolved = complaint(ComplaintCategory.DAMAGE, BASE, now.minusSeconds(1));
        resolved.resolve(now.minusSeconds(5));
        complaints.save(resolved);
        Complaint closed = complaint(ComplaintCategory.DAMAGE, BASE, now.minusSeconds(1));
        closed.close(now.minusSeconds(5));
        complaints.save(closed);
        complaints.flush();

        assertThat(complaints.findSlaBreachCandidates(now)).extracting(Complaint::getId)
                .containsExactlyInAnyOrder(breached.getId(), dueNow.getId(),
                        breachedRefundFailed.getId());
    }

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = Complaint.class)
    @EnableJpaRepositories(basePackageClasses = ComplaintRepository.class)
    static class JpaConfig {
    }
}
