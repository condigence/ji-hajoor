package com.jihajoor.rating.provider;

import java.math.BigDecimal;

import com.jihajoor.rating.domain.AggregateCalculator;

/**
 * A provider's aggregate rating as of one change to their counted customer reviews (Requirement
 * 15.4): what {@link ProviderRatingPort} sets in the Provider Service.
 *
 * @param average  the weighted aggregate, 0&ndash;5 at 2 dp; {@code null} when no review counts
 * @param count    the number of customer reviews the aggregate is over (active, not held by
 *                 moderation)
 * @param sequence the provider's change number this aggregate reflects (at least); higher is newer
 */
public record ProviderRatingSnapshot(BigDecimal average, int count, long sequence) {

    public static ProviderRatingSnapshot of(AggregateCalculator.Summary summary, long sequence) {
        return new ProviderRatingSnapshot(summary.average(), summary.count(), sequence);
    }
}
