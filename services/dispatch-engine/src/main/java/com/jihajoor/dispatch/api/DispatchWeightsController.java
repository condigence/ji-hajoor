package com.jihajoor.dispatch.api;

import com.jihajoor.dispatch.domain.DispatchSettingsValidationException;
import com.jihajoor.dispatch.domain.MatchingWeights;
import com.jihajoor.dispatch.domain.WeightValidationException;
import com.jihajoor.dispatch.service.DispatchSettingsService;
import com.jihajoor.shared.observability.error.ErrorResponseDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoint for viewing and updating the dispatch matching weights (Requirement 19.5,
 * Property 19). A submitted weight set is accepted if and only if every weight is in [0.0, 1.0]
 * and the five sum to exactly 1.0; otherwise the update is rejected with a 400 that names the
 * failing condition, and the existing weights are left unchanged.
 *
 * <p>The weights are part of the dispatch rules saved by {@link DispatchSettingsService}, so an
 * update here is persisted and reaches every replica like a save of the whole rules form; the
 * other rules are kept as currently saved.
 *
 * <p>RBAC (SUPER_ADMIN for System Configuration) is enforced upstream by the shared
 * {@code RbacEnforcementFilter}; this controller focuses on domain validation.
 */
@RestController
@RequestMapping("/admin/dispatch/weights")
public class DispatchWeightsController {

    private final DispatchSettingsService settingsService;
    private final CallerIdentity callerIdentity;

    public DispatchWeightsController(DispatchSettingsService settingsService, CallerIdentity callerIdentity) {
        this.settingsService = settingsService;
        this.callerIdentity = callerIdentity;
    }

    @GetMapping
    public MatchingWeights current() {
        return settingsService.latest().weights();
    }

    @PutMapping
    public MatchingWeights update(@Valid @RequestBody MatchingWeightsRequest request) {
        // ofExact validates range + exact sum before anything is saved; on failure it throws and the
        // saved weights are never touched, satisfying "leave existing weights unchanged" (Req 19.5).
        MatchingWeights candidate = MatchingWeights.ofExact(
                request.distanceWeight(),
                request.availabilityWeight(),
                request.ratingWeight(),
                request.skillWeight(),
                request.performanceWeight());
        return settingsService.update(current -> current.withWeights(candidate), callerIdentity.auditName())
                .weights();
    }

    @ExceptionHandler(WeightValidationException.class)
    public ResponseEntity<ErrorResponseDto> onInvalidWeights(WeightValidationException ex) {
        return badRequest("INVALID_DISPATCH_WEIGHTS", ex.getMessage());
    }

    /**
     * Only when the other rules in force are themselves out of range (configured defaults outside
     * the Admin bounds): the weights are then refused rather than saved alongside them.
     */
    @ExceptionHandler(DispatchSettingsValidationException.class)
    public ResponseEntity<ErrorResponseDto> onInvalidSettings(DispatchSettingsValidationException ex) {
        return badRequest("INVALID_DISPATCH_SETTINGS", ex.getMessage());
    }

    private static ResponseEntity<ErrorResponseDto> badRequest(String errorCode, String message) {
        ErrorResponseDto body = ErrorResponseDto.builder()
                .errorCode(errorCode)
                .message(message)
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
