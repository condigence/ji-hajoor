package com.jihajoor.dispatch.port;

import com.jihajoor.dispatch.domain.DispatchSettings;

import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Durable, shared storage for the Admin Portal's dispatch rules (Requirements 8.2, 8.4, 8.5, 8.8,
 * 19.5), so a save survives restarts and reaches every replica. The default adapter is
 * {@code JpaDispatchSettingsStore} ({@code dispatch.dispatch_settings}).
 *
 * <p>The store holds no defaults of its own: before the first save {@link #load} is empty and the
 * caller ({@code DispatchSettingsService}) uses the configured {@code jihajoor.dispatch.*} values.
 * It does not cache either; that is the caller's job.
 */
public interface DispatchSettingsStore {

    /**
     * @return the saved settings, or empty when none have been saved yet
     * @throws RuntimeException when the store cannot be read (e.g. the database is down) or holds
     *                          an invalid weight set
     */
    Optional<DispatchSettings> load();

    /**
     * Atomically replaces the saved settings with {@code change} applied to the settings saved
     * right now — read afresh, not from any cache — or to {@code unsaved} when nothing has been
     * saved yet. Concurrent updates, from this replica or another, are applied one after another,
     * so a partial update (one that keeps some of the current values) never undoes another.
     *
     * <p>{@code change} may throw to refuse the update (validation); nothing is written then and
     * the exception propagates unchanged.
     *
     * @param updatedBy who made the change, kept for audit; may be {@code null}
     * @return the settings now saved
     */
    DispatchSettings update(UnaryOperator<DispatchSettings> change, DispatchSettings unsaved, String updatedBy);
}
