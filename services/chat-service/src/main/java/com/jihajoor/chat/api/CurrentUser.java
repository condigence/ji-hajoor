package com.jihajoor.chat.api;

import java.util.UUID;

import com.jihajoor.chat.service.ChatException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** The authenticated caller of a chat REST endpoint. */
final class CurrentUser {

    private CurrentUser() {
    }

    /**
     * @return the authenticated user's ID, taken from the principal name populated by the shared
     *         {@code JwtValidationFilter} (Task 4).
     */
    static UUID id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ChatException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "no authenticated principal");
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            throw new ChatException(HttpStatus.UNAUTHORIZED, "INVALID_PRINCIPAL",
                    "authenticated principal is not a valid user ID");
        }
    }
}
