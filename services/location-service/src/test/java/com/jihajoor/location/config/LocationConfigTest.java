package com.jihajoor.location.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.jihajoor.location.booking.HttpBookingParticipantsAdapter;
import com.jihajoor.location.domain.Coordinates;
import com.jihajoor.location.eta.HaversineEtaCalculatorAdapter;
import com.jihajoor.location.store.RedisLocationStoreAdapter;
import com.jihajoor.location.subscription.InMemorySubscriberRegistry;
import com.jihajoor.shared.resilience.ResilienceFactory;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

/** The runtime wiring of the Location Service's ports. */
class LocationConfigTest {

    private final LocationAdaptersConfig adapters = new LocationAdaptersConfig();

    @Test
    void wiresTheProductionAdapters() {
        assertThat(adapters.locationStore(mock(StringRedisTemplate.class), JsonMapper.builder().build(),
                new LocationProperties())).isInstanceOf(RedisLocationStoreAdapter.class);
        assertThat(adapters.bookingParticipants(new ResilienceFactory(), "http://jobs-app:8084", "key"))
                .isInstanceOf(HttpBookingParticipantsAdapter.class);
        assertThat(adapters.subscriberRegistry()).isInstanceOf(InMemorySubscriberRegistry.class);

        HaversineEtaCalculatorAdapter.DestinationResolver resolver = adapters.destinationResolver();
        assertThat(resolver.destinationFor(UUID.randomUUID())).isEqualTo(new Coordinates(0.0, 0.0));
        assertThat(adapters.etaCalculator(resolver)).isInstanceOf(HaversineEtaCalculatorAdapter.class);
    }

    @Test
    void resilienceMetricsAreBoundWhenARegistryExists() {
        DefaultListableBeanFactory none = new DefaultListableBeanFactory();
        assertThat(new ResilienceConfig().resilienceFactory(none.getBeanProvider(MeterRegistry.class))).isNotNull();

        DefaultListableBeanFactory withRegistry = new DefaultListableBeanFactory();
        withRegistry.registerSingleton("meterRegistry", new SimpleMeterRegistry());
        assertThat(new ResilienceConfig().resilienceFactory(withRegistry.getBeanProvider(MeterRegistry.class)))
                .isNotNull();
    }

    @Test
    void theClockIsUtc() {
        assertThat(new ClockConfig().clock().getZone().getId()).isEqualTo("Z");
    }
}
