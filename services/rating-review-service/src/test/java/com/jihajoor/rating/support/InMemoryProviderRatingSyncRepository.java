package com.jihajoor.rating.support;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.data.domain.Pageable;

import com.jihajoor.rating.domain.ProviderRatingSyncRepository;
import com.jihajoor.rating.domain.ProviderRatingSyncState;

/**
 * In-memory {@link ProviderRatingSyncRepository} with the same semantics as the SQL: an upsert that
 * starts at sequence 1 and bumps by one, and a delivery mark that never moves backwards.
 */
public class InMemoryProviderRatingSyncRepository implements ProviderRatingSyncRepository {

    private final Map<UUID, ProviderRatingSyncState> rows = new ConcurrentHashMap<>();

    @Override
    public int markChanged(UUID providerId, Instant changedAt) {
        rows.merge(providerId, new ProviderRatingSyncState(providerId, 1, 0, changedAt),
                (old, fresh) -> new ProviderRatingSyncState(providerId, old.getChangeSequence() + 1,
                        old.getPushedSequence(), changedAt));
        return 1;
    }

    @Override
    public Optional<ProviderRatingSyncState> findById(UUID providerId) {
        return Optional.ofNullable(rows.get(providerId));
    }

    @Override
    public List<ProviderRatingSyncState> findOwed(Instant cutoff, Pageable page) {
        return rows.values().stream()
                .filter(ProviderRatingSyncState::isOwed)
                .filter(s -> !s.getChangedAt().isAfter(cutoff))
                .sorted(Comparator.comparing(ProviderRatingSyncState::getChangedAt))
                .limit(page.getPageSize())
                .toList();
    }

    @Override
    public int markPushed(UUID providerId, long sequence) {
        ProviderRatingSyncState old = rows.get(providerId);
        if (old == null || old.getPushedSequence() >= sequence) {
            return 0;
        }
        rows.put(providerId, new ProviderRatingSyncState(providerId, old.getChangeSequence(), sequence,
                old.getChangedAt()));
        return 1;
    }

    /** Seeds a row directly, e.g. one the V2 backfill created. */
    public void put(ProviderRatingSyncState state) {
        rows.put(state.getProviderId(), state);
    }
}
