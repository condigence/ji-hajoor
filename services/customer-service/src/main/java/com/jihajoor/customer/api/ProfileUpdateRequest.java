package com.jihajoor.customer.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code PUT /customers/{id}/profile} (Requirement 2.1).
 *
 * <p>The profile photo itself is uploaded separately (multipart) and validated for
 * JPEG/PNG type and 5 MB size; here we accept the resulting stored photo URL. Display
 * name must be 1-100 chars and the email must be well-formed.
 */
public record ProfileUpdateRequest(

        @NotBlank(message = "display name is required")
        @Size(min = 1, max = 100, message = "display name must be 1-100 characters")
        String displayName,

        @NotBlank(message = "email is required")
        @Email(message = "email must be a valid address")
        String email,

        // An https address or a path on this site, nothing else: the apps put it in <img src>, and
        // another scheme (javascript:, data:, http:) or a tracking host has no business there.
        @Size(max = 2048, message = "photoUrl must be at most 2048 characters")
        @Pattern(regexp = "^$|^https://[^\\s\"'<>]+$|^/[^/\\s\"'<>][^\\s\"'<>]*$",
                message = "photoUrl must be an https address")
        String photoUrl) {
}
