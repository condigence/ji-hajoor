package com.jihajoor.verification.storage;

/**
 * The document store could not be written or read right now: S3 refused or timed out, or the local
 * disk failed. The caller should treat it as retriable; {@code VerificationService} answers it with
 * 503 {@code DOCUMENT_STORAGE_UNAVAILABLE}. "Nothing stored under that key" is not this exception
 * but an empty result.
 */
public class DocumentStorageException extends RuntimeException {

    public DocumentStorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public DocumentStorageException(String message) {
        super(message);
    }
}
