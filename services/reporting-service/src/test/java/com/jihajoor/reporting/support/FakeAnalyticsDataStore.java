package com.jihajoor.reporting.support;

import java.util.ArrayList;
import java.util.List;

import com.jihajoor.reporting.analytics.AnalyticsDataStorePort;
import com.jihajoor.reporting.domain.ReportFilters;
import com.jihajoor.reporting.domain.ReportRow;
import com.jihajoor.reporting.domain.ReportType;

/**
 * Test double for {@link AnalyticsDataStorePort} that returns a preconfigured set of rows,
 * defaulting to empty so the "no data" path is easy to exercise (Requirement 20.2).
 */
public class FakeAnalyticsDataStore implements AnalyticsDataStorePort {

    private final List<ReportRow> rows = new ArrayList<>();

    public FakeAnalyticsDataStore withRows(ReportRow... newRows) {
        rows.clear();
        rows.addAll(List.of(newRows));
        return this;
    }

    @Override
    public List<ReportRow> query(ReportType type, ReportFilters filters) {
        return List.copyOf(rows);
    }
}
