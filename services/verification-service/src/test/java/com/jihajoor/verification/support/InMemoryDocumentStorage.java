package com.jihajoor.verification.support;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.jihajoor.verification.domain.DocumentStorageKind;
import com.jihajoor.verification.storage.DocumentContent;
import com.jihajoor.verification.storage.DocumentFile;
import com.jihajoor.verification.storage.DocumentStorage;
import com.jihajoor.verification.storage.DocumentStoragePort;

/**
 * A {@link DocumentStoragePort} that keeps bytes in a map, posing as the local store, for tests that
 * drive the verification workflow without caring where documents go.
 */
public final class InMemoryDocumentStorage implements DocumentStoragePort {

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    /** A {@link DocumentStorage} writing to a fresh in-memory store. */
    public static DocumentStorage create() {
        return new DocumentStorage(new InMemoryDocumentStorage());
    }

    @Override
    public DocumentStorageKind kind() {
        return DocumentStorageKind.LOCAL;
    }

    @Override
    public String put(String key, DocumentFile file) {
        objects.put(key, file.bytes().clone());
        return key;
    }

    @Override
    public Optional<DocumentContent> get(String storedKey) {
        byte[] bytes = objects.get(storedKey);
        return bytes == null
                ? Optional.empty()
                : Optional.of(new DocumentContent(new ByteArrayInputStream(bytes), bytes.length));
    }
}
