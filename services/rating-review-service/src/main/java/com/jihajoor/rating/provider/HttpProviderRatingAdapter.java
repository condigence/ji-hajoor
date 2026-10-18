package com.jihajoor.rating.provider;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Production {@link ProviderRatingPort}: sets the aggregate through the Provider Service's
 * {@code PUT /internal/providers/{providerId}/rating} with the shared {@code X-Internal-Api-Key}
 * (Requirements 4.7, 15.4). The default; {@code jihajoor.rating.provider-rating.client=logging}
 * selects {@link LoggingProviderRatingAdapter} instead, for running without a Provider Service.
 *
 * <p><strong>Why a synchronous push of the computed aggregate, not a Kafka consumer in the Provider
 * Service.</strong> This service owns the reviews and the aggregate formula (recency weighting,
 * flagged and removed reviews left out), and the aggregate changes on events that publish no
 * {@code ReviewSubmitted} at all: an Admin removing a review, or approving a flagged one after its
 * event was withheld. A consumer of {@code ReviewSubmitted} would have to duplicate the formula, keep
 * its own copy of every review, and still miss removals. The Provider Service also has no Kafka
 * consumer infrastructure today. Pushing the whole aggregate as an idempotent, sequence-ordered set
 * keeps the formula in one place, covers every path that changes it, and makes retries safe;
 * durability comes from {@link ProviderRatingSync}'s owed marker and sweeper rather than from a
 * broker.
 *
 * <p>No resilience wrapper here, as in the Payment Service's wallet adapter: the call runs after the
 * review commit, and every failure leaves the rating owed for {@code ProviderRatingSweeper} to
 * re-send, so a failure is simply reported as a {@link ProviderRatingUpdateException}, marked
 * permanent when the Provider Service refused the update for good. Timeouts are short because the
 * first attempt runs on the thread that submitted the review.
 */
@Component
@ConditionalOnProperty(name = "jihajoor.rating.provider-rating.client", havingValue = "http",
        matchIfMissing = true)
public class HttpProviderRatingAdapter implements ProviderRatingPort {

    private static final Logger log = LoggerFactory.getLogger(HttpProviderRatingAdapter.class);

    /** Header carrying the shared service credential the Provider Service expects on /internal/**. */
    static final String INTERNAL_KEY_HEADER = "X-Internal-Api-Key";

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    private final RestClient restClient;

    public HttpProviderRatingAdapter(
            @Value("${jihajoor.rating.provider-service-url:http://provider-service:8083}") String baseUrl,
            @Value("${jihajoor.rating.internal-api-key:}") String internalApiKey) {
        if (internalApiKey == null || internalApiKey.isBlank()) {
            // Not fatal: every push is then refused with 401 and stays owed, so ratings catch up once
            // the key is configured. Failing startup would take every review endpoint down with it.
            log.error("jihajoor.rating.internal-api-key (INTERNAL_API_KEY) is not configured; the Provider "
                    + "Service will refuse every rating update, so provider ratings will not change");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        requestFactory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(INTERNAL_KEY_HEADER, internalApiKey == null ? "" : internalApiKey)
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public void updateAggregateRating(UUID providerId, ProviderRatingSnapshot rating) {
        try {
            restClient.put()
                    .uri("/internal/providers/{providerId}/rating", providerId)
                    .body(new RatingRequest(rating.average(), rating.count(), rating.sequence()))
                    .retrieve()
                    .toBodilessEntity();
            log.debug("Provider {} rating set to {} over {} review(s) (sequence {})",
                    providerId, rating.average(), rating.count(), rating.sequence());
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            throw new ProviderRatingUpdateException("Provider Service refused the rating for provider "
                    + providerId + " (HTTP " + status + "): " + e.getMessage(), e, isPermanentRefusal(status));
        } catch (RestClientException e) {
            throw new ProviderRatingUpdateException("Provider Service did not confirm the rating for provider "
                    + providerId + ": " + e.getMessage(), e, false);
        }
    }

    /**
     * Whether the Provider Service's answer is a refusal no re-send can change: 400 (an invalid
     * aggregate), 404 (no such provider) and 422. Other 4xx are not: 401/403 mean this service's
     * credential is wrong, which an operator fixes, after which the owed ratings must still go
     * through; 408, 409 and 429 are worth retrying.
     */
    static boolean isPermanentRefusal(int status) {
        return status == 400 || status == 404 || status == 422;
    }

    /** Body of the Provider Service's rating endpoint ({@code ProviderRatingRequest} there). */
    record RatingRequest(BigDecimal average, int count, long sequence) {
    }
}
