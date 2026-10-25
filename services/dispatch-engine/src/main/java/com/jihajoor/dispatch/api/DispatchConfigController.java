package com.jihajoor.dispatch.api;

import com.jihajoor.dispatch.domain.DispatchSettingsValidationException;
import com.jihajoor.dispatch.domain.WeightValidationException;
import com.jihajoor.dispatch.service.DispatchSettingsService;
import com.jihajoor.shared.observability.error.ErrorResponseDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin Portal endpoint for the dispatch rules — matching weights plus search radius and offer
 * timeout — read and written as one form (Requirements 8.2, 8.4, 8.5, 8.8, 19.5).
 *
 * <p>The weights obey the same accept-iff-valid rule as {@link DispatchWeightsController} and fail
 * with the same {@code INVALID_DISPATCH_WEIGHTS} code; the radius / cycle / timeout values fail
 * with {@code INVALID_DISPATCH_SETTINGS}. Either way nothing is changed. Updates are saved in the
 * database, so they survive restarts and reach every replica's new dispatches within the settings
 * cache lifetime (see {@link DispatchSettingsService}); the GET reads the saved rules afresh.
 *
 * <p>RBAC is enforced upstream by the shared {@code RbacEnforcementFilter}: dispatchers may read the
 * rules they work under, but changing them is System Configuration and needs SUPER_ADMIN
 * ({@code DispatchRbacConfig}).
 */
@RestController
@RequestMapping("/admin/dispatch/config")
public class DispatchConfigController {

    private final DispatchSettingsService settingsService;
    private final CallerIdentity callerIdentity;

    public DispatchConfigController(DispatchSettingsService settingsService, CallerIdentity callerIdentity) {
        this.settingsService = settingsService;
        this.callerIdentity = callerIdentity;
    }

    @GetMapping
    public DispatchConfigDto current() {
        return DispatchConfigDto.from(settingsService.latest());
    }

    @PutMapping
    public DispatchConfigDto update(@Valid @RequestBody DispatchConfigDto request) {
        // toSettings validates the weights before the service validates the rest, and the service
        // saves nothing until everything has passed. The omitted optional fields are taken from the
        // rules saved at that moment, not from this replica's cache.
        return DispatchConfigDto.from(
                settingsService.update(request::toSettings, callerIdentity.auditName()));
    }

    @ExceptionHandler(WeightValidationException.class)
    public ResponseEntity<ErrorResponseDto> onInvalidWeights(WeightValidationException ex) {
        return badRequest("INVALID_DISPATCH_WEIGHTS", ex.getMessage());
    }

    @ExceptionHandler(DispatchSettingsValidationException.class)
    public ResponseEntity<ErrorResponseDto> onInvalidSettings(DispatchSettingsValidationException ex) {
        return badRequest("INVALID_DISPATCH_SETTINGS", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponseDto> onMissingField(MethodArgumentNotValidException ex) {
        ErrorResponseDto.Builder body = ErrorResponseDto.builder()
                .errorCode("VALIDATION_ERROR")
                .message("Request validation failed");
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> body.addDetail(fe.getField() + ": " + fe.getDefaultMessage()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body.build());
    }

    private static ResponseEntity<ErrorResponseDto> badRequest(String errorCode, String message) {
        ErrorResponseDto body = ErrorResponseDto.builder()
                .errorCode(errorCode)
                .message(message)
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
