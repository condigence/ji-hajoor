package com.jihajoor.verification.storage;

/**
 * Framework-agnostic view of an uploaded verification document, so the storage port is testable
 * without a servlet {@code MultipartFile}.
 *
 * @param originalFilename the client's file name, or {@code null}
 * @param contentType      the client's MIME type, or {@code null}
 * @param bytes            the file content
 */
public record DocumentFile(String originalFilename, String contentType, byte[] bytes) {

    public DocumentFile {
        bytes = bytes == null ? new byte[0] : bytes;
    }
}
