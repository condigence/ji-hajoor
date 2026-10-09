package com.jihajoor.verification.service;

import com.jihajoor.verification.domain.DocumentType;

/**
 * A single document to upload during the {@code POST /verifications/{providerId}/documents}
 * flow (Requirement 5.3).
 */
public record DocumentUpload(DocumentType type, byte[] content, String contentType) {
}
