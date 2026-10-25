package com.jihajoor.verification.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A single uploaded verification document (Requirement 5.3). Only the storage key and metadata are
 * persisted here; the file bytes live in the store {@link #getStorage()} names (S3 with server-side
 * encryption in production, local disk otherwise), and are served to admins through
 * {@code GET /admin/verification/{providerId}/documents/{documentId}}.
 *
 * <p>Rows recorded before the service kept document bytes have no {@code storage} and an
 * {@code s3://...} reference that was never written: they read back as "not stored".
 */
@Entity
@Table(name = "verification_document")
public class VerificationDocument {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "verification_id", nullable = false)
    private Verification verification;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 40)
    private DocumentType documentType;

    /**
     * The key within {@link #storage} the bytes were written under (the S3 object key, prefix
     * included, or the path under the local root). On rows without a {@code storage} it is the
     * legacy {@code s3://bucket/key} reference of an object that was never written.
     */
    @Column(name = "storage_ref", nullable = false, length = 512)
    private String storageRef;

    /** Which store holds the bytes; {@code null} when none does (rows from before bytes were kept). */
    @Enumerated(EnumType.STRING)
    @Column(name = "storage", length = 10)
    private DocumentStorageKind storage;

    /** The file name the provider uploaded, sanitised; {@code null} when unknown. */
    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "size_bytes")
    private long sizeBytes;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    protected VerificationDocument() {
        // JPA
    }

    VerificationDocument(UUID id, Verification verification, DocumentType documentType,
                         DocumentStorageKind storage, String storageRef, String fileName,
                         String contentType, long sizeBytes) {
        this.id = id;
        this.verification = verification;
        this.documentType = documentType;
        this.storage = storage;
        this.storageRef = storageRef;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.uploadedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public DocumentType getDocumentType() {
        return documentType;
    }

    public String getStorageRef() {
        return storageRef;
    }

    /** Which store holds the bytes, or {@code null} when they were never stored. */
    public DocumentStorageKind getStorage() {
        return storage;
    }

    /** Whether the bytes were stored and can be served. */
    public boolean isStored() {
        return storage != null;
    }

    public String getFileName() {
        return fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }
}
