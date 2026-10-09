package com.jihajoor.auth.api;

import com.jihajoor.auth.domain.AccountStatus;

import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code PATCH /admin/users/{id}/status}: the portal's {@code { status }} payload. A
 * missing or unknown status is a 400 {@code VALIDATION_ERROR}.
 */
public record UpdateUserStatusRequest(@NotNull AccountStatus status) {
}
