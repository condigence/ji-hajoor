package com.jihajoor.rating.provider;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.jihajoor.rating.alert.AdminAlertPort;
import com.jihajoor.rating.config.RatingProperties;

/**
 * {@link ProviderRatingPort} for running without a Provider Service, selected with
 * {@code jihajoor.rating.provider-rating.client=logging}: logs the aggregate instead of setting it,
 * so provider ratings shown elsewhere do not change.
 *
 * <p>It stands in for the Provider Service's UNDER_REVIEW decision too, in memory: an aggregate
 * below {@code jihajoor.rating.under-review-threshold} flags the provider and alerts Admin once
 * (Requirements 15.7, 15.8). The set is lost on restart; the HTTP adapter leaves the decision, and
 * the alert, to the Provider Service, which persists the flag.
 */
@Component
@ConditionalOnProperty(name = "jihajoor.rating.provider-rating.client", havingValue = "logging")
public class LoggingProviderRatingAdapter implements ProviderRatingPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingProviderRatingAdapter.class);

    private final RatingProperties properties;
    private final AdminAlertPort adminAlertPort;
    private final Set<UUID> underReview = ConcurrentHashMap.newKeySet();

    public LoggingProviderRatingAdapter(RatingProperties properties, AdminAlertPort adminAlertPort) {
        this.properties = properties;
        this.adminAlertPort = adminAlertPort;
    }

    @Override
    public void updateAggregateRating(UUID providerId, ProviderRatingSnapshot rating) {
        log.info("PROVIDER_RATING provider={} aggregate={} count={} sequence={} (logging adapter: not "
                        + "sent to the Provider Service)",
                providerId, rating.average(), rating.count(), rating.sequence());
        BigDecimal threshold = properties.getUnderReviewThreshold();
        if (rating.count() > 0 && rating.average().compareTo(threshold) < 0
                && underReview.add(providerId)) {
            log.warn("Provider {} flagged UNDER_REVIEW", providerId);
            adminAlertPort.providerBelowThreshold(providerId, rating.average(), threshold);
        }
    }

    /** Whether this adapter has flagged the provider UNDER_REVIEW. */
    public boolean isUnderReview(UUID providerId) {
        return underReview.contains(providerId);
    }
}
