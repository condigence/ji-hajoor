package com.jihajoor.reporting.report;

import java.util.Objects;

import com.jihajoor.reporting.domain.ExportFormat;
import com.jihajoor.reporting.domain.ReportFilters;
import com.jihajoor.reporting.domain.ReportType;

/**
 * A validated command to generate a report: the report type, the filters (date range plus optional
 * dimensions), the desired export format, and the requestor's email for the asynchronous
 * notification path (Requirement 20.3, 20.4). The email is PII and is never logged
 * (Requirement 26.4).
 */
public record ReportRequest(
        ReportType type,
        ReportFilters filters,
        ExportFormat format,
        String requestorEmail) {

    public ReportRequest {
        Objects.requireNonNull(type, "report type is required");
        Objects.requireNonNull(filters, "filters are required");
        Objects.requireNonNull(format, "export format is required");
    }
}
