package com.jihajoor.dispatch.domain;

import java.util.Objects;

/**
 * A snapshot of every Admin-tunable dispatch setting (Requirements 8.2, 8.4, 8.5, 8.8, 19.5): the
 * matching weights, the search-radius and offer-timeout parameters, how many providers each wave
 * offers the job to at once, and after how many minutes an unaccepted booking goes to the agencies.
 *
 * <p>The Admin Portal's single "dispatch rules" form reads and writes it as one value, it is what
 * {@code dispatch.dispatch_settings} stores, and {@code DispatchService} takes one snapshot from
 * {@code DispatchSettingsService} when a search starts and uses it for the whole search, so a
 * search never mixes values from before and after an Admin save.
 */
public record DispatchSettings(
        MatchingWeights weights,
        double initialRadiusKm,
        double radiusIncrementKm,
        int maxExpansionCycles,
        long offerTimeoutSeconds,
        int offerBatchSize,
        long agencyFallbackMinutes) {

    public DispatchSettings {
        Objects.requireNonNull(weights, "weights must not be null");
    }

    public DispatchSettings withWeights(MatchingWeights newWeights) {
        return new DispatchSettings(newWeights, initialRadiusKm, radiusIncrementKm, maxExpansionCycles,
                offerTimeoutSeconds, offerBatchSize, agencyFallbackMinutes);
    }

    public DispatchSettings withOfferTimeoutSeconds(long seconds) {
        return new DispatchSettings(weights, initialRadiusKm, radiusIncrementKm, maxExpansionCycles,
                seconds, offerBatchSize, agencyFallbackMinutes);
    }

    public DispatchSettings withOfferBatchSize(int size) {
        return new DispatchSettings(weights, initialRadiusKm, radiusIncrementKm, maxExpansionCycles,
                offerTimeoutSeconds, size, agencyFallbackMinutes);
    }

    public DispatchSettings withAgencyFallbackMinutes(long minutes) {
        return new DispatchSettings(weights, initialRadiusKm, radiusIncrementKm, maxExpansionCycles,
                offerTimeoutSeconds, offerBatchSize, minutes);
    }
}
