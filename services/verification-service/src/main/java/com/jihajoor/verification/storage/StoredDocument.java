package com.jihajoor.verification.storage;

import com.jihajoor.verification.domain.DocumentStorageKind;

/**
 * Where an upload was written: the two facts a {@code verification_document} row needs to read it
 * back.
 *
 * @param kind the store holding the bytes
 * @param key  the key within that store
 */
public record StoredDocument(DocumentStorageKind kind, String key) {
}
