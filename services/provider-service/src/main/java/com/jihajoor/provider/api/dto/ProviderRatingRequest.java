package com.jihajoor.provider.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code PUT /internal/providers/{providerId}/rating}: the Rating &amp; Review Service setting
 * a provider's aggregate rating over their customer reviews (Requirements 4.7, 15.4).
 *
 * <p>The shape is the Rating &amp; Review Service's contract ({@code HttpProviderRatingAdapter
 * .RatingRequest}); field names must not change without changing it there.
 *
 * @param average  the weighted aggregate of the counted reviews, 0&ndash;5 at most 2 dp; required
 *                 when {@code count > 0}, {@code null} (or 0) when {@code count} is 0
 * @param count    the number of customer reviews the aggregate is over
 * @param sequence the sender's change number for this provider's aggregate; a push with a lower
 *                 number than the one already applied is ignored as stale
 */
public record ProviderRatingRequest(
        @DecimalMin("0.00") @DecimalMax("5.00") @Digits(integer = 1, fraction = 2) BigDecimal average,
        @NotNull @Min(0) Integer count,
        @NotNull @Min(0) Long sequence) {

    /**
     * An aggregate needs reviews to be over, and no reviews has no aggregate. Reported as the
     * {@code averageConsistentWithCount} field.
     */
    @AssertTrue(message = "average is required when count is above 0, and must be null or 0 when count is 0")
    public boolean isAverageConsistentWithCount() {
        if (count == null) {
            return true; // reported by @NotNull on count
        }
        return count == 0 ? average == null || average.signum() == 0 : average != null;
    }
}
