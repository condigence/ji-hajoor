package com.jihajoor.provider.domain;

/**
 * What {@link ProviderProfile#applyRatingAggregate} did with an aggregate pushed by the Rating &amp;
 * Review Service.
 */
public enum RatingUpdateOutcome {

    /**
     * Ignored: the profile already holds an aggregate with a higher sequence, so this one is older
     * (it arrived late). Nothing changed. Not an error: the newer aggregate is already in place.
     */
    STALE,

    /** Stored. The provider was not newly flagged for review. */
    APPLIED,

    /**
     * Stored, and the aggregate dropped below the review threshold while the provider was not yet
     * flagged, so the provider is now UNDER_REVIEW (Requirement 4.7). The caller alerts Admin once.
     */
    FLAGGED_FOR_REVIEW;

    /** Whether the aggregate was stored. */
    public boolean applied() {
        return this != STALE;
    }
}
