package com.jihajoor.verification.api;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.jihajoor.verification.domain.VerificationDocument;
import com.jihajoor.verification.service.VerificationService.DocumentDownload;

/**
 * Builds the response that streams a verification document's bytes, shared by the admin and the
 * provider endpoints.
 *
 * <h2>Headers</h2>
 * <ul>
 *   <li>{@code Cache-Control: private, no-store}: identity documents are never kept by a browser or
 *       an intermediary cache.</li>
 *   <li>{@code X-Content-Type-Options: nosniff}, and the stored content type is used only when it is
 *       one of {@link #INLINE_TYPES} (images a browser renders, and PDF). Anything else is the
 *       provider's claim about an arbitrary upload, so it is served as
 *       {@code application/octet-stream} with {@code Content-Disposition: attachment}: a file
 *       declared {@code text/html} is never rendered on the API's origin.</li>
 *   <li>{@code Content-Disposition} carries the original file name when one was recorded.</li>
 * </ul>
 */
final class DocumentResponses {

    /** Content types served as themselves, inline. */
    static final Set<String> INLINE_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif", "application/pdf");

    static final String CACHE_CONTROL = "private, no-store";

    private DocumentResponses() {
    }

    static ResponseEntity<Resource> stream(DocumentDownload download) {
        VerificationDocument document = download.document();
        String declared = normalise(document.getContentType());
        boolean inline = declared != null && INLINE_TYPES.contains(declared);
        MediaType type = inline ? MediaType.parseMediaType(declared) : MediaType.APPLICATION_OCTET_STREAM;

        ContentDisposition.Builder disposition = inline ? ContentDisposition.inline() : ContentDisposition.attachment();
        if (document.getFileName() != null) {
            disposition.filename(document.getFileName(), StandardCharsets.UTF_8);
        }

        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.build().toString());
        if (download.content().length() >= 0) {
            response.contentLength(download.content().length());
        }
        // Streamed, not buffered: the converter copies the stream to the response and closes it.
        return response.body(new InputStreamResource(download.content().stream()));
    }

    /** The bare, lower-case MIME type ({@code image/png}), or {@code null} when absent or malformed. */
    static String normalise(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        try {
            MediaType parsed = MediaType.parseMediaType(contentType);
            return (parsed.getType() + "/" + parsed.getSubtype()).toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
