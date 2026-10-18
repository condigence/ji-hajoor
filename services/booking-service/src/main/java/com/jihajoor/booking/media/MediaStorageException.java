package com.jihajoor.booking.media;

/**
 * The media store could not be written or read right now: S3 refused or timed out, or the local
 * disk failed. The caller should treat it as retriable; {@code MediaService} answers it with
 * 503 {@code MEDIA_STORAGE_UNAVAILABLE}. "Nothing stored under that key" is not this exception but
 * an empty result.
 */
public class MediaStorageException extends RuntimeException {

    public MediaStorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public MediaStorageException(String message) {
        super(message);
    }
}
