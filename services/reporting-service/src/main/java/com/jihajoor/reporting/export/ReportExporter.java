package com.jihajoor.reporting.export;

import com.jihajoor.reporting.domain.ExportFormat;
import com.jihajoor.reporting.domain.Report;

/**
 * Renders a {@link Report} to a byte payload in a specific {@link ExportFormat} (Requirement 20.4).
 * Each supported format has one implementation; {@link ReportExportService} selects among them.
 */
public interface ReportExporter {

    /** The format this exporter produces. */
    ExportFormat format();

    /** The MIME content type of the produced payload (for the HTTP {@code Content-Type} header). */
    String contentType();

    /** Renders the report to bytes in this exporter's format. */
    byte[] export(Report report);
}
