package com.jihajoor.provider.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.jihajoor.provider.service.ProviderService.RatingApplication;

/**
 * Answer to {@code PUT /internal/providers/{providerId}/rating}: the rating the provider now holds.
 *
 * @param applied     {@code false} when the push was older than the aggregate already held (a lower
 *                    sequence) and was ignored; the fields then describe the newer aggregate
 * @param underReview whether the provider is flagged UNDER_REVIEW (and so skipped by dispatch)
 */
public record ProviderRatingResponse(
        UUID providerId,
        BigDecimal aggregateRating,
        int ratingCount,
        long ratingSequence,
        boolean underReview,
        boolean applied) {

    public static ProviderRatingResponse from(RatingApplication application) {
        var p = application.profile();
        return new ProviderRatingResponse(p.getId(), p.getAggregateRating(), p.getRatingCount(),
                p.getRatingSequence(), p.isUnderReview(), application.outcome().applied());
    }
}
