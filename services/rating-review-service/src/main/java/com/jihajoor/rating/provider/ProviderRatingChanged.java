package com.jihajoor.rating.provider;

import java.util.UUID;

/**
 * In-process event: the set of reviews that count toward a provider's aggregate changed in the
 * current transaction. {@link ProviderRatingSync} pushes the new aggregate once that transaction
 * has committed. Not published to Kafka.
 */
public record ProviderRatingChanged(UUID providerId) {
}
