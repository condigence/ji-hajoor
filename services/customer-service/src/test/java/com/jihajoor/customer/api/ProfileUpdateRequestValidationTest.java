package com.jihajoor.customer.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

/** A profile photo address must be https (or a path on this site): it ends up in an img tag. */
class ProfileUpdateRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private boolean valid(String photoUrl) {
        return validator.validate(new ProfileUpdateRequest("Asha", "asha@example.com", photoUrl)).isEmpty();
    }

    @Test
    void acceptsHttpsSitePathsEmptyAndNull() {
        assertThat(valid("https://cdn.jihajoor.com/p/1.jpg")).isTrue();
        assertThat(valid("/media/customers/1.jpg")).isTrue();
        assertThat(valid("")).isTrue();
        assertThat(valid(null)).isTrue();
    }

    @Test
    void refusesOtherSchemesProtocolRelativeAndMarkup() {
        assertThat(valid("javascript:alert(1)")).isFalse();
        assertThat(valid("data:image/svg+xml;base64,PHN2Zz4=")).isFalse();
        assertThat(valid("http://tracker.example/pixel.gif")).isFalse();
        assertThat(valid("//evil.example/x.png")).isFalse();
        assertThat(valid("https://x.example/a\"onerror=\"alert(1)")).isFalse();
    }
}
