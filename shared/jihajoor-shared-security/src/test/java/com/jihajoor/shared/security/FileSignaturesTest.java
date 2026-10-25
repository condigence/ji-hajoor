package com.jihajoor.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class FileSignaturesTest {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static byte[] iso(String box, String brand) {
        byte[] b = new byte[16];
        b[3] = 0x18;
        System.arraycopy(box.getBytes(StandardCharsets.ISO_8859_1), 0, b, 4, 4);
        System.arraycopy(brand.getBytes(StandardCharsets.ISO_8859_1), 0, b, 8, 4);
        return b;
    }

    @Test
    void recognisesTheAcceptedFormats() {
        assertThat(FileSignatures.detect(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0))).contains("image/jpeg");
        assertThat(FileSignatures.detect(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A))).contains("image/png");
        assertThat(FileSignatures.detect("%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII))).contains("application/pdf");
        assertThat(FileSignatures.detect("RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1))).contains("image/webp");
        assertThat(FileSignatures.detect(iso("ftyp", "heic"))).contains("image/heic");
        assertThat(FileSignatures.detect(iso("ftyp", "isom"))).contains("video/mp4");
        assertThat(FileSignatures.detect(iso("ftyp", "qt  "))).contains("video/quicktime");
    }

    @Test
    void refusesScriptsPagesAndExecutablesWhateverTheyClaimToBe() {
        assertThat(FileSignatures.detect("<svg onload=alert(1)>".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(FileSignatures.detect("<!doctype html>".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(FileSignatures.detect(bytes('M', 'Z', 0x90, 0x00))).isEmpty();
        assertThat(FileSignatures.detect(new byte[2])).isEmpty();
        assertThat(FileSignatures.matches("<html>".getBytes(StandardCharsets.UTF_8), "image/jpeg")).isFalse();
    }

    @Test
    void matchesTheDeclaredTypeAllowingItsAliases() {
        byte[] jpeg = bytes(0xFF, 0xD8, 0xFF, 0xDB);
        assertThat(FileSignatures.matches(jpeg, "image/jpeg")).isTrue();
        assertThat(FileSignatures.matches(jpeg, "IMAGE/JPG")).isTrue();
        assertThat(FileSignatures.matches(jpeg, "image/png")).isFalse();
        assertThat(FileSignatures.matches(iso("ftyp", "isom"), "video/quicktime")).isTrue();
        assertThat(FileSignatures.matches(iso("moov", "mvhd"), "video/mp4")).isTrue();
        assertThat(FileSignatures.matches(jpeg, null)).isFalse();
    }
}
