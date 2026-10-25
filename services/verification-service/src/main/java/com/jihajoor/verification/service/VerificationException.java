package com.jihajoor.verification.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * Domain exception for verification-workflow failures, carrying the HTTP status, a stable
 * error code, and optional detail lines that the REST layer surfaces via the shared
 * {@code ErrorResponseDto}.
 */
public class VerificationException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final List<String> details = new ArrayList<>();

    public VerificationException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public VerificationException(HttpStatus status, String errorCode, String message, List<String> details) {
        this(status, errorCode, message);
        if (details != null) {
            this.details.addAll(details);
        }
    }

    public static VerificationException notFound(String message) {
        return new VerificationException(HttpStatus.NOT_FOUND, "VERIFICATION_NOT_FOUND", message);
    }

    /** 404: no document {@code documentId} on this provider's record. */
    public static VerificationException documentNotFound(java.util.UUID documentId) {
        return new VerificationException(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND",
                "No document " + documentId + " on this provider's verification record");
    }

    /**
     * 404: the document is on record but its bytes are not stored, e.g. it was uploaded before the
     * service kept document content.
     */
    public static VerificationException documentNotStored(java.util.UUID documentId) {
        return new VerificationException(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_STORED",
                "Document " + documentId + " is on record but its file is not stored "
                        + "(uploaded before document storage was enabled)");
    }

    /** 503: the document store cannot be written or read right now; retriable. */
    public static VerificationException documentStorageUnavailable() {
        return new VerificationException(HttpStatus.SERVICE_UNAVAILABLE, "DOCUMENT_STORAGE_UNAVAILABLE",
                "Document storage is unavailable right now; try again shortly");
    }

    public static VerificationException validation(String message) {
        return new VerificationException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public List<String> getDetails() {
        return details;
    }
}
