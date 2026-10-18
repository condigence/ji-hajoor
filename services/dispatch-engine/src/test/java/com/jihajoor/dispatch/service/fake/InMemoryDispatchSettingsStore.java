package com.jihajoor.dispatch.service.fake;

import com.jihajoor.dispatch.domain.DispatchSettings;
import com.jihajoor.dispatch.port.DispatchSettingsStore;

import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * In-memory {@link DispatchSettingsStore} standing in for the shared
 * {@code dispatch.dispatch_settings} row, so the settings service, the controllers and the
 * dispatch loop can be tested without a database. {@link #saveElsewhere} plays another replica's
 * save; {@link #failWith} an unreachable database.
 */
public class InMemoryDispatchSettingsStore implements DispatchSettingsStore {

    private DispatchSettings saved;
    private String lastUpdatedBy;
    private RuntimeException failure;
    private int loads;
    private int updates;

    @Override
    public synchronized Optional<DispatchSettings> load() {
        loads++;
        if (failure != null) {
            throw failure;
        }
        return Optional.ofNullable(saved);
    }

    @Override
    public synchronized DispatchSettings update(UnaryOperator<DispatchSettings> change, DispatchSettings unsaved,
                                                String updatedBy) {
        if (failure != null) {
            throw failure;
        }
        // Like the real store: the change runs on what is saved now, and a throw writes nothing.
        DispatchSettings next = change.apply(saved != null ? saved : unsaved);
        saved = next;
        lastUpdatedBy = updatedBy;
        updates++;
        return next;
    }

    /** Saves {@code settings} the way another replica would: without this replica's service knowing. */
    public synchronized void saveElsewhere(DispatchSettings settings) {
        saved = settings;
    }

    /** Makes every call throw {@code failure} until {@link #recover()}. */
    public synchronized void failWith(RuntimeException failure) {
        this.failure = failure;
    }

    public synchronized void recover() {
        this.failure = null;
    }

    public synchronized Optional<DispatchSettings> saved() {
        return Optional.ofNullable(saved);
    }

    public synchronized String lastUpdatedBy() {
        return lastUpdatedBy;
    }

    public synchronized int loads() {
        return loads;
    }

    public synchronized int updates() {
        return updates;
    }
}
