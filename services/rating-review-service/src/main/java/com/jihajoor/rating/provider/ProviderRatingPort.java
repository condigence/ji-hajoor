package com.jihajoor.rating.provider;

import java.util.UUID;

/**
 * Sets a provider's aggregate rating where the rest of the platform reads it: the Provider Service,
 * whose profile feeds the Dispatch Engine's rating score, the provider app and the Admin lists
 * (Requirements 4.7, 15.4). Modelled as a port so the transport can vary and so it is mockable in
 * unit tests.
 *
 * <p><strong>Contract.</strong> An idempotent <em>set</em> of the whole aggregate, never an
 * increment, and ordered by {@link ProviderRatingSnapshot#sequence()}: a snapshot older than the one
 * already held is ignored. Delivering the same snapshot twice, or an old one late, therefore changes
 * nothing, which is what lets {@link ProviderRatingSync} re-send freely.
 *
 * <p><strong>UNDER_REVIEW.</strong> The receiver owns the provider account, so it also decides the
 * below-threshold UNDER_REVIEW flag (Requirements 4.7, 15.7) as part of the same update, and alerts
 * Admin once when it sets it (15.8). Dispatch reads that flag from the Provider Service, so it must
 * live there; a flag kept here would be lost on restart and invisible to dispatch.
 */
public interface ProviderRatingPort {

    /**
     * Sets the provider's aggregate rating to {@code rating}.
     *
     * @throws ProviderRatingUpdateException when the update could not be confirmed;
     *         {@link ProviderRatingUpdateException#isPermanent()} tells a refusal no retry can change
     *         from a failure worth retrying
     */
    void updateAggregateRating(UUID providerId, ProviderRatingSnapshot rating);
}
