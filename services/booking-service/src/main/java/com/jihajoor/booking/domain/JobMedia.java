package com.jihajoor.booking.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * Reference to a media file attached to a booking and stored in object storage (design
 * {@code JOB_MEDIA}, Requirement 7.2).
 *
 * <p>Only the storage key and metadata are stored here; the bytes live behind the
 * {@code MediaStoragePort} adapter named by {@link #getStorage()}: an S3 object, or a file on the
 * local disk of the instance that accepted the upload. The column holding the key is still called
 * {@code s3_key} for both, since renaming it would gain nothing but a migration.
 */
@Entity
@Table(name = "job_media", indexes = {
        @Index(name = "idx_job_media_booking", columnList = "booking_id")
})
public class JobMedia {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    /** Logical media role, e.g. CUSTOMER_UPLOAD, BEFORE_PHOTO, AFTER_PHOTO. */
    @Column(name = "type", nullable = false, length = 40)
    private String type;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "s3_key", nullable = false, length = 1024)
    private String s3Key;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    /** Which store holds the bytes under {@link #s3Key} (V5; earlier rows are LOCAL). */
    @Enumerated(EnumType.STRING)
    @Column(name = "storage", nullable = false, length = 10)
    private MediaStorageKind storage;

    protected JobMedia() {
        // JPA
    }

    private JobMedia(UUID id, UUID bookingId, String type, String contentType, long sizeBytes,
                     MediaStorageKind storage, String s3Key) {
        this.id = id;
        this.bookingId = bookingId;
        this.type = type;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.storage = storage;
        this.s3Key = s3Key;
        this.uploadedAt = Instant.now();
    }

    /** A LOCAL row with a fresh id, for callers (mostly tests) that do not care where bytes live. */
    public static JobMedia of(UUID bookingId, String type, String contentType, long sizeBytes, String s3Key) {
        return new JobMedia(UUID.randomUUID(), bookingId, type, contentType, sizeBytes,
                MediaStorageKind.LOCAL, s3Key);
    }

    /**
     * The row for bytes already written to {@code storage} under {@code key}. The id is chosen by
     * the caller before the upload, because the storage key embeds it
     * ({@code bookings/{bookingId}/{id}.{ext}}).
     */
    public static JobMedia stored(UUID id, UUID bookingId, String type, String contentType, long sizeBytes,
                                  MediaStorageKind storage, String key) {
        return new JobMedia(id, bookingId, type, contentType, sizeBytes, storage, key);
    }

    public UUID getId() {
        return id;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public String getType() {
        return type;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getS3Key() {
        return s3Key;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public MediaStorageKind getStorage() {
        return storage;
    }
}
