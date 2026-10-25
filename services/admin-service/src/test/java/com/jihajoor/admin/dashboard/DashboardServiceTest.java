package com.jihajoor.admin.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.jihajoor.admin.api.DashboardController;

/** The dashboard snapshot: computed at start, replaced on each refresh, served as-is. */
class DashboardServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void theStubReportsZeros() {
        DashboardMetrics metrics = new DashboardService(new StubDashboardMetricsSource(), CLOCK).currentMetrics();

        assertThat(metrics.activeBookings()).isZero();
        assertThat(metrics.activeProvidersOnline()).isZero();
        assertThat(metrics.newRegistrationsLast24h()).isZero();
        assertThat(metrics.grossRevenueLast24h()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(metrics.avgProviderResponseTimeSeconds()).isZero();
        assertThat(metrics.openComplaintCount()).isZero();
        assertThat(metrics.platformRating()).isZero();
        assertThat(metrics.refreshedAt()).isEqualTo(CLOCK.instant());
    }

    @Test
    void refreshReplacesTheSnapshot_andTheControllerServesIt() throws Exception {
        AtomicLong bookings = new AtomicLong(3);
        DashboardMetricsSource source = new StubDashboardMetricsSource() {
            @Override
            public long activeBookings() {
                return bookings.get();
            }
        };
        DashboardService service = new DashboardService(source, CLOCK);
        assertThat(service.currentMetrics().activeBookings()).isEqualTo(3);

        bookings.set(9);
        assertThat(service.currentMetrics().activeBookings()).isEqualTo(3);
        service.refresh();
        assertThat(service.currentMetrics().activeBookings()).isEqualTo(9);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DashboardController(service)).build();
        mvc.perform(get("/admin/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeBookings").value(9));
    }
}
