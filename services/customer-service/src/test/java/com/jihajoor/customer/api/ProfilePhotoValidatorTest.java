package com.jihajoor.customer.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import com.jihajoor.customer.service.CustomerException;

/**
 * Unit tests for profile-photo validation: JPEG/PNG only, ≤ 5 MB (Requirement 2.1).
 */
class ProfilePhotoValidatorTest {

    private static byte[] withHead(byte[] body, int... head) {
        for (int i = 0; i < head.length; i++) {
            body[i] = (byte) head[i];
        }
        return body;
    }

    @Test
    void rejectsAScriptDeclaredAsJpeg() {
        MockMultipartFile photo = new MockMultipartFile(
                "photo", "p.jpg", "image/jpeg", "<script>alert(1)</script>".getBytes());
        assertThatThrownBy(() -> ProfilePhotoValidator.validate(photo))
                .isInstanceOf(CustomerException.class)
                .satisfies(ex -> assertThat(((CustomerException) ex).getErrorCode())
                        .isEqualTo("INVALID_PHOTO_TYPE"));
    }

    @Test
    void acceptsJpegWithinSize() {
        MockMultipartFile photo = new MockMultipartFile(
                "photo", "p.jpg", "image/jpeg", withHead(new byte[1024], 0xFF, 0xD8, 0xFF, 0xE0));
        assertThatCode(() -> ProfilePhotoValidator.validate(photo)).doesNotThrowAnyException();
    }

    @Test
    void acceptsPngWithinSize() {
        MockMultipartFile photo = new MockMultipartFile(
                "photo", "p.png", "image/png", withHead(new byte[1024], 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A));
        assertThatCode(() -> ProfilePhotoValidator.validate(photo)).doesNotThrowAnyException();
    }

    @Test
    void nullPhotoIsAllowed() {
        assertThatCode(() -> ProfilePhotoValidator.validate(null)).doesNotThrowAnyException();
    }

    @Test
    void rejectsUnsupportedType() {
        MockMultipartFile photo = new MockMultipartFile(
                "photo", "p.gif", "image/gif", new byte[1024]);
        assertThatThrownBy(() -> ProfilePhotoValidator.validate(photo))
                .isInstanceOf(CustomerException.class)
                .satisfies(ex -> assertThat(((CustomerException) ex).getErrorCode())
                        .isEqualTo("INVALID_PHOTO_TYPE"));
    }

    @Test
    void rejectsOversizePhoto() {
        byte[] tooBig = new byte[(int) (5L * 1024 * 1024 + 1)];
        MockMultipartFile photo = new MockMultipartFile(
                "photo", "big.png", "image/png", tooBig);
        assertThatThrownBy(() -> ProfilePhotoValidator.validate(photo))
                .isInstanceOf(CustomerException.class)
                .satisfies(ex -> assertThat(((CustomerException) ex).getErrorCode())
                        .isEqualTo("PHOTO_TOO_LARGE"));
    }
}
