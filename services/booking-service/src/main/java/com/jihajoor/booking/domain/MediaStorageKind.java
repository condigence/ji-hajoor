package com.jihajoor.booking.domain;

/**
 * Where the bytes of a {@link JobMedia} row live, recorded per row so a photo is always read back
 * from the store it was written to, whatever the service is configured to write to today.
 *
 * <p>The distinction matters because the store a process writes to can change: a deployment turns
 * S3 on, or a replica falls back to local disk because S3 was unreachable when it started
 * ({@code MediaStorage}). Without a per-row kind, turning S3 on would make every photo stored
 * before it unreadable, since the reader would look for it in the bucket.
 */
public enum MediaStorageKind {

    /**
     * The local filesystem of the instance that accepted the upload. Readable only by that
     * instance (or by every instance sharing the directory, e.g. a mounted volume). Rows created
     * before the {@code storage} column existed are LOCAL; the adapter of that time kept no bytes,
     * so their content is not available.
     */
    LOCAL,

    /** An object in the configured S3 (or S3-compatible) bucket; readable by every instance. */
    S3
}
