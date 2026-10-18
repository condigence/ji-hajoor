package com.jihajoor.verification.storage;

import java.util.Optional;

import com.jihajoor.verification.domain.DocumentStorageKind;

/**
 * Port abstracting the object store verification documents are kept in (Requirement 5.3): S3 in
 * production ({@link S3DocumentStorageAdapter}), the local disk otherwise
 * ({@link LocalDocumentStorageAdapter}).
 *
 * <p>An adapter only moves bytes under a key it is given. Choosing the key, choosing which adapter
 * to write to, and finding the adapter a stored row must be read from are {@link DocumentStorage}'s;
 * the business logic talks to that, not to an adapter.
 */
public interface DocumentStoragePort {

    /** The kind recorded on a {@code verification_document} row written through this adapter. */
    DocumentStorageKind kind();

    /**
     * Stores {@code file} under {@code key} ({@code providers/{providerId}/{TYPE}/{documentId}.{ext}}).
     *
     * @return the key to persist and later pass to {@link #get}: {@code key} itself, or with the
     *         adapter's own prefix applied
     * @throws DocumentStorageException when the store cannot take the file right now
     */
    String put(String key, DocumentFile file);

    /**
     * The bytes stored under {@code storedKey}.
     *
     * @return empty when nothing is stored under that key
     * @throws DocumentStorageException when the store cannot be read right now
     */
    Optional<DocumentContent> get(String storedKey);
}
