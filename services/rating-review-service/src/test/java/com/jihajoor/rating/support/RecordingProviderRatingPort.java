package com.jihajoor.rating.support;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jihajoor.rating.provider.ProviderRatingPort;
import com.jihajoor.rating.provider.ProviderRatingSnapshot;
import com.jihajoor.rating.provider.ProviderRatingUpdateException;

/**
 * Recording {@link ProviderRatingPort} test double: captures every rating pushed per provider, and
 * can be told to fail, transiently or permanently, the way the Provider Service can.
 */
public class RecordingProviderRatingPort implements ProviderRatingPort {

    private final Map<UUID, List<ProviderRatingSnapshot>> pushes = new HashMap<>();
    private ProviderRatingUpdateException failure;

    @Override
    public void updateAggregateRating(UUID providerId, ProviderRatingSnapshot rating) {
        if (failure != null) {
            throw failure;
        }
        pushes.computeIfAbsent(providerId, id -> new ArrayList<>()).add(rating);
    }

    /** Every later push fails with a transient (Provider Service unavailable) failure. */
    public void failTransiently() {
        failure = new ProviderRatingUpdateException("Provider Service unavailable", null, false);
    }

    /** Every later push is refused for good (e.g. 404 unknown provider). */
    public void refusePermanently() {
        failure = new ProviderRatingUpdateException("HTTP 404", null, true);
    }

    /** Pushes succeed again. */
    public void recover() {
        failure = null;
    }

    /** The latest aggregate pushed for the provider, or {@code null} if none (or none counted). */
    public BigDecimal lastAggregate(UUID providerId) {
        ProviderRatingSnapshot last = last(providerId);
        return last == null ? null : last.average();
    }

    /** The latest snapshot pushed for the provider, or {@code null} if nothing was pushed. */
    public ProviderRatingSnapshot last(UUID providerId) {
        List<ProviderRatingSnapshot> forProvider = pushes.get(providerId);
        return forProvider == null || forProvider.isEmpty() ? null : forProvider.get(forProvider.size() - 1);
    }

    /** Every snapshot pushed for the provider, in order. */
    public List<ProviderRatingSnapshot> pushes(UUID providerId) {
        return pushes.getOrDefault(providerId, List.of());
    }
}
