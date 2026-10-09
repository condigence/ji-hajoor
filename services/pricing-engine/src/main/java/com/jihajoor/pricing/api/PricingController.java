package com.jihajoor.pricing.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jihajoor.pricing.api.dto.OverrideRequestDto;
import com.jihajoor.pricing.api.dto.PriceBreakdownDto;
import com.jihajoor.pricing.api.dto.PriceRequestDto;
import com.jihajoor.pricing.service.PriceQuoteService;
import com.jihajoor.pricing.service.PricingConfigService;
import com.jihajoor.pricing.service.ProviderOverrideService;

import jakarta.validation.Valid;

/**
 * Price-estimate and provider-override endpoints (Requirement 6.9, 6.10, 6.12).
 *
 * <p>Typically consumed by the Booking Service to obtain an itemised estimate before booking
 * confirmation. Requires an authenticated principal (see {@code WebSecurityConfig}).
 */
@RestController
@RequestMapping("/pricing")
public class PricingController {

    private final PriceQuoteService quoteService;
    private final ProviderOverrideService overrideService;
    private final PricingConfigService configService;

    public PricingController(PriceQuoteService quoteService,
                             ProviderOverrideService overrideService,
                             PricingConfigService configService) {
        this.quoteService = quoteService;
        this.overrideService = overrideService;
        this.configService = configService;
    }

    /** Returns a fully itemised price estimate (Requirement 6.9). */
    @PostMapping("/estimate")
    public ResponseEntity<PriceBreakdownDto> estimate(@Valid @RequestBody PriceRequestDto request) {
        return ResponseEntity.ok(PriceBreakdownDto.from(quoteService.quote(request.toDomain())));
    }

    /** Validates a provider-specific override against the subcategory bounds (Requirement 6.12). */
    @PostMapping("/overrides")
    public ResponseEntity<OverrideRequestDto> validateOverride(
            @Valid @RequestBody OverrideRequestDto request) {
        overrideService.validateOverride(request.proposedPrice(),
                configService.requireParameters(request.subcategoryId()));
        return ResponseEntity.ok(request);
    }
}
