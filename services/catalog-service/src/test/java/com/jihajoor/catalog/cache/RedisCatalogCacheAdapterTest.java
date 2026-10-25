package com.jihajoor.catalog.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.jihajoor.catalog.api.dto.CategoryView;
import com.jihajoor.catalog.api.dto.SubcategoryView;
import com.jihajoor.catalog.config.CatalogProperties;
import com.jihajoor.catalog.config.RedisCacheConfig;

import tools.jackson.databind.json.JsonMapper;

/** The Redis catalog cache: round trip, misses, unreadable entries and best-effort writes. */
class RedisCatalogCacheAdapterTest {

    private final AtomicReference<String> stored = new AtomicReference<>();
    private RedisTemplate<String, String> redis;
    private ValueOperations<String, String> ops;
    private RedisCatalogCacheAdapter cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(RedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        org.mockito.Mockito.doAnswer(inv -> {
            stored.set(inv.getArgument(1));
            return null;
        }).when(ops).set(eq(CatalogCachePort.ACTIVE_CATALOG_KEY), anyString(), any(Duration.class));
        when(ops.get(CatalogCachePort.ACTIVE_CATALOG_KEY)).thenAnswer(inv -> stored.get());
        cache = new RedisCatalogCacheAdapter(redis, JsonMapper.builder().build(), new CatalogProperties());
    }

    private static List<CategoryView> catalog() {
        UUID category = UUID.randomUUID();
        return List.of(new CategoryView(category, "Plumbing", "Pipes", null, 1, List.of(
                new SubcategoryView(UUID.randomUUID(), category, "Tap repair", new BigDecimal("299.00"), 45,
                        List.of("plumbing"), true))));
    }

    @Test
    void roundTripsTheActiveCatalog() {
        List<CategoryView> catalog = catalog();
        assertThat(cache.getActiveCatalog()).isEmpty();

        cache.putActiveCatalog(catalog);

        assertThat(cache.getActiveCatalog()).contains(catalog);
    }

    @Test
    void anUnreadableEntryIsAMiss() {
        stored.set("{broken");
        assertThat(cache.getActiveCatalog()).isEmpty();
    }

    @Test
    void aFailedWriteDoesNotBreakTheRequest() {
        doThrow(new IllegalStateException("redis down")).when(ops).set(anyString(), anyString(), any(Duration.class));
        cache.putActiveCatalog(catalog());
        assertThat(stored.get()).isNull();
    }

    @Test
    void invalidateDeletesTheEntry() {
        cache.invalidate();
        verify(redis).delete(CatalogCachePort.ACTIVE_CATALOG_KEY);
    }

    @Test
    void theTemplateUsesStringSerialisers() {
        RedisTemplate<String, String> template =
                new RedisCacheConfig().catalogRedisTemplate(mock(RedisConnectionFactory.class));
        assertThat(template.getKeySerializer()).isNotNull();
        assertThat(template.getValueSerializer()).isNotNull();
    }
}
