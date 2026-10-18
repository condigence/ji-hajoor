package com.jihajoor.verification.api.dto;

import java.util.UUID;

import com.jihajoor.verification.domain.DocumentType;
import com.jihajoor.verification.domain.VerificationDocument;

/**
 * One entry of {@code GET /admin/verification/{providerId}/documents}, shaped exactly as the Admin
 * Portal's {@code VerificationDocument} (Requirement 19.3).
 *
 * <p>{@code url} is the API path the document's bytes are served from,
 * {@code /admin/verification/{providerId}/documents/{documentId}}, relative to the API base. It is
 * not a presigned storage URL: the endpoint needs the admin's bearer token, so the portal fetches it
 * through its API client rather than an {@code <img src>}. It is {@code null} for a document whose
 * bytes were never stored (uploaded before documents were kept), which the portal shows as "not
 * stored". The raw storage reference is never exposed. {@code fileName} is the provider's original
 * file name, {@code null} for documents uploaded before it was recorded.
 */
public record VerificationDocumentResponse(
        UUID id,
        String type,
        String contentType,
        String url,
        String fileName) {

    public static VerificationDocumentResponse from(UUID providerId, VerificationDocument document) {
        return new VerificationDocumentResponse(
                document.getId(),
                label(document.getDocumentType()),
                document.getContentType(),
                document.isStored() ? contentPath(providerId, document.getId()) : null,
                document.getFileName());
    }

    /** The API path of a document's bytes ({@code AdminVerificationQueueController#document}). */
    public static String contentPath(UUID providerId, UUID documentId) {
        return "/admin/verification/" + providerId + "/documents/" + documentId;
    }

    /** The human label the portal shows for a document type. */
    static String label(DocumentType type) {
        return switch (type) {
            case GOVERNMENT_ID -> "Government ID";
            case ADDRESS_PROOF -> "Address proof";
            case SKILL_CERTIFICATION -> "Skill certification";
        };
    }
}
