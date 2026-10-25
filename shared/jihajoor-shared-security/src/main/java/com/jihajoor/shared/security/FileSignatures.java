package com.jihajoor.shared.security;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Recognises an uploaded file by its first bytes rather than by the type the client claims, so a
 * script, HTML page or executable cannot be stored as a "photo" or a "document". Only the formats
 * the platform accepts are known; anything else is {@link Optional#empty()}.
 */
public final class FileSignatures {

    private static final Set<String> ISO_IMAGE_BRANDS = Set.of(
            "heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1", "avif", "avis");

    private static final Set<String> QUICKTIME_ATOMS = Set.of("moov", "mdat", "wide", "free", "skip", "pnot");

    private FileSignatures() {
    }

    /**
     * The MIME type the content is, judged from its signature: {@code image/jpeg},
     * {@code image/png}, {@code image/gif}, {@code image/webp}, {@code image/heic},
     * {@code image/avif}, {@code image/bmp}, {@code image/tiff}, {@code application/pdf},
     * {@code video/mp4} or {@code video/quicktime}.
     */
    public static Optional<String> detect(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return Optional.empty();
        }
        if (starts(bytes, 0xFF, 0xD8, 0xFF)) {
            return Optional.of("image/jpeg");
        }
        if (starts(bytes, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of("image/png");
        }
        if (ascii(bytes, 0, 6).equals("GIF87a") || ascii(bytes, 0, 6).equals("GIF89a")) {
            return Optional.of("image/gif");
        }
        if (ascii(bytes, 0, 4).equals("RIFF") && ascii(bytes, 8, 4).equals("WEBP")) {
            return Optional.of("image/webp");
        }
        if (ascii(bytes, 0, 5).equals("%PDF-")) {
            return Optional.of("application/pdf");
        }
        if (starts(bytes, 'B', 'M')) {
            return Optional.of("image/bmp");
        }
        if (starts(bytes, 'I', 'I', 0x2A, 0x00) || starts(bytes, 'M', 'M', 0x00, 0x2A)) {
            return Optional.of("image/tiff");
        }
        String box = ascii(bytes, 4, 4);
        if (box.equals("ftyp")) {
            String brand = ascii(bytes, 8, 4).toLowerCase(Locale.ROOT);
            if (ISO_IMAGE_BRANDS.contains(brand)) {
                return Optional.of(brand.startsWith("avi") ? "image/avif" : "image/heic");
            }
            return Optional.of(brand.equals("qt  ") ? "video/quicktime" : "video/mp4");
        }
        if (QUICKTIME_ATOMS.contains(box)) {
            return Optional.of("video/quicktime");
        }
        return Optional.empty();
    }

    /**
     * True when the content really is {@code declaredType}. MP4 and QuickTime share a container
     * format, so either signature satisfies either declaration; HEIC and HEIF likewise.
     */
    public static boolean matches(byte[] bytes, String declaredType) {
        if (declaredType == null) {
            return false;
        }
        String declared = normalise(declaredType);
        return detect(bytes).map(FileSignatures::normalise).filter(declared::equals).isPresent();
    }

    private static String normalise(String type) {
        String t = type.toLowerCase(Locale.ROOT).strip();
        int semicolon = t.indexOf(';');
        if (semicolon >= 0) {
            t = t.substring(0, semicolon).strip();
        }
        return switch (t) {
            case "image/jpg", "image/pjpeg" -> "image/jpeg";
            case "image/heif" -> "image/heic";
            case "video/quicktime" -> "video/mp4";
            default -> t;
        };
    }

    private static boolean starts(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static String ascii(byte[] bytes, int from, int length) {
        if (bytes.length < from + length) {
            return "";
        }
        return new String(bytes, from, length, StandardCharsets.ISO_8859_1);
    }
}
