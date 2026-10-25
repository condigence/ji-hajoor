package com.jihajoor.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

import com.jihajoor.customer.config.ClockConfig;

class DeletionAnonymizationSchedulerTest {

    @Test
    void eachSweepAnonymisesTheDueRequests() {
        CustomerProfileService service = mock(CustomerProfileService.class);
        new DeletionAnonymizationScheduler(service).sweep();
        verify(service).anonymizeDueRequests();
    }

    @Test
    void theClockIsUtc() {
        assertThat(new ClockConfig().clock().getZone().getId()).isEqualTo("Z");
    }
}
