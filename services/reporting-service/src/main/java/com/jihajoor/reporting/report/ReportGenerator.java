package com.jihajoor.reporting.report;

import java.util.List;

import org.springframework.stereotype.Component;

import com.jihajoor.reporting.analytics.AnalyticsDataStorePort;
import com.jihajoor.reporting.domain.Report;
import com.jihajoor.reporting.domain.ReportFilters;
import com.jihajoor.reporting.domain.ReportRow;
import com.jihajoor.reporting.domain.ReportType;

/**
 * Materialises a {@link Report} from the analytics store for a given type and filter set.
 *
 * <p>When the store returns no rows, the generator produces the empty-result report carrying the
 * "no data" message and the report's column headers (Requirement 20.2). This is used by both the
 * synchronous and asynchronous paths so the empty-handling is identical.
 */
@Component
public class ReportGenerator {

    private final AnalyticsDataStorePort analyticsDataStore;

    public ReportGenerator(AnalyticsDataStorePort analyticsDataStore) {
        this.analyticsDataStore = analyticsDataStore;
    }

    public Report generate(ReportType type, ReportFilters filters) {
        List<String> columns = ReportCatalog.columnsFor(type);
        List<ReportRow> rows = analyticsDataStore.query(type, filters);
        if (rows.isEmpty()) {
            return Report.empty(type, columns);
        }
        return Report.of(type, columns, rows);
    }
}
