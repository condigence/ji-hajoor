package com.jihajoor.verification.domain;

/**
 * Where the bytes of a {@link VerificationDocument} live, recorded per row so a document is always
 * read back from the store it was written to, whatever the service is configured to write to today.
 *
 * <p>The store a process writes to can change: a deployment turns S3 on, or a replica falls back
 * to local disk because S3 was unreachable when it started ({@code DocumentStorage}). Without a
 * per-row kind, turning S3 on would make every document stored on disk before it unreadable.
 *
 * <p>A row with no kind ({@code storage} is {@code NULL}) was recorded before the service kept
 * document bytes at all: the old adapter only derived an {@code s3://} reference and logged it.
 * Such a document has no content to serve and reads back as "not stored" (404).
 */
public enum DocumentStorageKind {

    /**
     * The local filesystem of the instance that accepted the upload. Readable only by that
     * instance (or by every instance sharing the directory, e.g. a mounted volume).
     */
    LOCAL,

    /** An object in the configured S3 (or S3-compatible) bucket; readable by every instance. */
    S3
}
