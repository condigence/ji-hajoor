package com.jihajoor.complaint.domain;

/**
 * One row of the per-category complaint count behind the Admin stats report (Requirement 16.9),
 * produced by {@link ComplaintRepository#countByCategory()}.
 *
 * @param category the complaint category
 * @param count    how many complaints carry it
 */
public record ComplaintCategoryCount(ComplaintCategory category, Long count) {
}
