package com.jihajoor.verification.service;

import com.jihajoor.verification.domain.DocumentType;

/**
 * A single document to upload during the {@code POST /verifications/{providerId}/documents}
 * flow (Requirement 5.3).
 *
 * @param type             which required document this is
 * @param content          the file bytes
 * @param contentType      the client's MIME type, or {@code null}
 * @param originalFilename the client's file name, or {@code null}; sanitised before it is kept
 */
public record DocumentUpload(DocumentType type, byte[] content, String contentType, String originalFilename) {

    /** An upload without a file name. */
    public DocumentUpload(DocumentType type, byte[] content, String contentType) {
        this(type, content, contentType, null);
    }
}
