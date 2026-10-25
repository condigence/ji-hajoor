package com.jihajoor.location.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.jihajoor.location.config.LocationProperties;
import com.jihajoor.location.domain.CachedLocation;
import com.jihajoor.location.domain.Coordinates;

import tools.jackson.databind.json.JsonMapper;

/** The Redis last-known-location cache over an in-memory stand-in for Redis. */
class RedisLocationStoreAdapterTest {

    private final Map<String, String> data = new HashMap<>();
    private StringRedisTemplate redis;
    private RedisLocationStoreAdapter store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        org.mockito.Mockito.doAnswer(inv -> data.put(inv.getArgument(0), inv.getArgument(1)))
                .when(ops).set(anyString(), anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
        when(ops.get(anyString())).thenAnswer(inv -> data.get(inv.<String>getArgument(0)));
        when(redis.delete(anyString())).thenAnswer(inv -> data.remove(inv.<String>getArgument(0)) != null);
        store = new RedisLocationStoreAdapter(redis, JsonMapper.builder().build(), new LocationProperties());
    }

    @Test
    void savesAndFindsTheLatestPosition_thenEvictsIt() {
        UUID booking = UUID.randomUUID();
        UUID provider = UUID.randomUUID();
        CachedLocation location = new CachedLocation(booking, provider, new Coordinates(25.55, 84.66),
                Instant.parse("2026-10-25T10:15:30Z"));

        store.save(location);

        assertThat(store.findLatest(booking)).contains(location);
        assertThat(data).containsKey("location:latest:" + booking);

        store.evict(booking);

        assertThat(store.findLatest(booking)).isEmpty();
        assertThat(data).isEmpty();
    }

    @Test
    void nothingStoredMeansNothingFound_andEvictingNothingIsHarmless() {
        UUID booking = UUID.randomUUID();
        assertThat(store.findLatest(booking)).isEmpty();
        store.evict(booking);
        verify(redis).delete("location:latest:" + booking);
    }

    @Test
    void aPointerWithoutItsPositionFindsNothing() {
        UUID booking = UUID.randomUUID();
        data.put("location:latest:" + booking, UUID.randomUUID().toString());
        assertThat(store.findLatest(booking)).isEmpty();
    }

    @Test
    void aCorruptEntryIsReportedNotSwallowed() {
        UUID booking = UUID.randomUUID();
        UUID provider = UUID.randomUUID();
        data.put("location:latest:" + booking, provider.toString());
        data.put("location:" + booking + ":" + provider, "{not json");
        assertThatThrownBy(() -> store.findLatest(booking)).isInstanceOf(IllegalStateException.class);
        verify(redis.opsForValue()).get(eq("location:" + booking + ":" + provider));
    }
}
