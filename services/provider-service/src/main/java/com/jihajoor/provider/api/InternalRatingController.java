package com.jihajoor.provider.api;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jihajoor.provider.api.dto.ProviderRatingRequest;
import com.jihajoor.provider.api.dto.ProviderRatingResponse;
import com.jihajoor.provider.service.ProviderService;

import jakarta.validation.Valid;

/**
 * Service-to-service write surface for the Rating &amp; Review Service: setting a provider's
 * aggregate rating over their customer reviews (Requirements 4.7, 15.4).
 *
 * <p>The Rating &amp; Review Service owns the reviews and computes the aggregate (recent reviews
 * weighted higher, flagged and removed reviews left out); this service stores it, because the
 * Dispatch Engine's eligibility search ({@code GET /internal/providers/eligible}), the provider's
 * profile and the Admin lists read it from here. It is pushed whenever the provider's counted
 * reviews change: a review submitted, a flagged review approved, a review removed.
 *
 * <p>Reachable only with the shared {@code X-Internal-Api-Key} credential, like
 * {@link InternalProviderController}. Idempotent and order-safe: the aggregate is set, not
 * accumulated, and a push with a lower {@code sequence} than the one held is ignored, so the sender
 * may retry any push it could not confirm.
 */
@RestController
@RequestMapping("/internal/providers")
public class InternalRatingController {

    private final ProviderService providerService;

    public InternalRatingController(ProviderService providerService) {
        this.providerService = providerService;
    }

    /**
     * {@code PUT /internal/providers/{providerId}/rating} &mdash; set the provider's aggregate rating.
     *
     * @return 200 with the rating now held ({@code applied=false} when the push was stale and
     *         ignored); 404 {@code PROVIDER_NOT_FOUND} for an unknown provider; 400
     *         {@code VALIDATION_ERROR} for an average outside 0&ndash;5, a negative count or
     *         sequence, or an average missing although reviews count
     */
    @PutMapping("/{providerId}/rating")
    public ResponseEntity<ProviderRatingResponse> setRating(
            @PathVariable("providerId") UUID providerId,
            @Valid @RequestBody ProviderRatingRequest request) {
        return ResponseEntity.ok(ProviderRatingResponse.from(providerService.applyRatingAggregate(
                providerId, request.average(), request.count(), request.sequence())));
    }
}
