package com.jihajoor.auth.api;

/**
 * The refresh token, for the native apps. Web apps send no body (or a null token): theirs is in
 * the HttpOnly {@code jh_refresh} cookie (see {@link RefreshCookie}).
 */
public record RefreshRequest(String refreshToken) {
}
