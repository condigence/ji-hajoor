package com.jihajoor.complaint.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.jihajoor.complaint.domain.Complaint;
import com.jihajoor.complaint.domain.ComplaintCategory;
import com.jihajoor.complaint.domain.ComplaintCategoryCount;
import com.jihajoor.complaint.domain.ComplaintResolutionTotals;

/**
 * Pure aggregation of complaint statistics for Admin reports (Requirement 16.9): per-category
 * counts, resolution rate, and average resolution time. Stateless and side-effect free so it is
 * trivially unit-testable and reusable by both the scheduled refresh and the query endpoint.
 *
 * <p>{@link #fromTotals} is what the service uses: it assembles the snapshot from the database's
 * {@code GROUP BY}/aggregate rows, so no complaint is loaded. {@link #aggregate} is the same
 * computation over loaded complaints, kept as the reference the database path is tested against.
 */
public class ComplaintStatsCalculator {

    /**
     * Assembles a {@link ComplaintStats} snapshot from database aggregates: the per-category counts
     * (categories with no row count zero) and the resolution totals. Gives the same result as
     * {@link #aggregate} over the same complaints: the per-complaint times arrive floored to the
     * millisecond and their mean is floored to the millisecond, as there.
     */
    public ComplaintStats fromTotals(List<ComplaintCategoryCount> categoryCounts,
                                     ComplaintResolutionTotals resolution) {
        Map<ComplaintCategory, Long> counts = new EnumMap<>(ComplaintCategory.class);
        for (ComplaintCategory category : ComplaintCategory.values()) {
            counts.put(category, 0L);
        }
        long total = 0;
        for (ComplaintCategoryCount row : categoryCounts) {
            long count = row.count() == null ? 0L : row.count();
            counts.merge(row.category(), count, Long::sum);
            total += count;
        }

        long resolved = resolution == null || resolution.resolved() == null
                ? 0L : resolution.resolved();
        Duration averageResolutionTime = Duration.ZERO;
        if (resolved > 0 && resolution.resolutionMillis() != null) {
            // The driver may hand back a BigDecimal (numeric sum) or a Long; floor-divide exactly.
            BigDecimal millis = new BigDecimal(resolution.resolutionMillis().toString());
            averageResolutionTime = Duration.ofMillis(millis
                    .divide(BigDecimal.valueOf(resolved), 0, RoundingMode.FLOOR)
                    .longValueExact());
        }
        double resolutionRate = total == 0 ? 0.0 : (double) resolved / total;
        return new ComplaintStats(counts, total, resolved, resolutionRate, averageResolutionTime);
    }

    /** Aggregates {@code complaints} into a {@link ComplaintStats} snapshot. */
    public ComplaintStats aggregate(List<Complaint> complaints) {
        Map<ComplaintCategory, Long> counts = new EnumMap<>(ComplaintCategory.class);
        for (ComplaintCategory category : ComplaintCategory.values()) {
            counts.put(category, 0L);
        }

        long total = 0;
        long resolved = 0;
        long resolutionMillisSum = 0;

        for (Complaint c : complaints) {
            total++;
            counts.merge(c.getCategory(), 1L, Long::sum);
            if (c.getStatus().isTerminal() && c.getResolvedAt() != null) {
                resolved++;
                resolutionMillisSum += Duration.between(c.getCreatedAt(), c.getResolvedAt()).toMillis();
            }
        }

        double resolutionRate = total == 0 ? 0.0 : (double) resolved / total;
        Duration averageResolutionTime = resolved == 0
                ? Duration.ZERO
                : Duration.ofMillis(resolutionMillisSum / resolved);

        return new ComplaintStats(counts, total, resolved, resolutionRate, averageResolutionTime);
    }
}
