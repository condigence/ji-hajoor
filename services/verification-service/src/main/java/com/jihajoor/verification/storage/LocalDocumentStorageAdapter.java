package com.jihajoor.verification.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jihajoor.verification.domain.DocumentStorageKind;

/**
 * {@link DocumentStoragePort} on the local filesystem, under one root directory
 * ({@code jihajoor.verification.document-storage.local.directory}). Used in development, in CI, and
 * wherever S3 is not configured or was unreachable at startup ({@link DocumentStorage}).
 *
 * <p>A file written here is visible only to the instances that see the same directory: on a
 * multi-replica deployment without a shared volume, a document uploaded through one replica is a
 * 404 on the others, and an ephemeral container disk loses it on redeploy. That is the trade-off of
 * the fallback, which is why {@code DocumentStorage} warns whenever it chooses this adapter over a
 * configured bucket.
 *
 * <p>Files are written to a temporary sibling and moved into place, so a reader never sees half a
 * file. Keys come from {@code DocumentStorage} and the database, never from a request, but are
 * still confined to the root: a key that would resolve outside it is refused.
 */
public class LocalDocumentStorageAdapter implements DocumentStoragePort {

    private static final Logger log = LoggerFactory.getLogger(LocalDocumentStorageAdapter.class);

    private final Path root;

    public LocalDocumentStorageAdapter(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public DocumentStorageKind kind() {
        return DocumentStorageKind.LOCAL;
    }

    /** The root directory files are written under. */
    public Path root() {
        return root;
    }

    @Override
    public String put(String key, DocumentFile file) {
        Path target = resolve(key);
        Path temp = null;
        try {
            Files.createDirectories(target.getParent());
            temp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            Files.write(temp, file.bytes());
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new DocumentStorageException("Could not write document to local storage at " + target, e);
        }
        log.debug("Local document storage: stored {} bytes contentType={} at {}",
                file.bytes().length, file.contentType(), target);
        return key;
    }

    @Override
    public Optional<DocumentContent> get(String storedKey) {
        Path file = resolve(storedKey);
        try {
            long size = Files.size(file);
            return Optional.of(new DocumentContent(Files.newInputStream(file), size));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new DocumentStorageException("Could not read document from local storage at " + file, e);
        }
    }

    private Path resolve(String key) {
        Path path;
        try {
            path = root.resolve(key).normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Not a valid document key: " + key, e);
        }
        if (!path.startsWith(root) || path.equals(root)) {
            throw new IllegalArgumentException("Document key escapes the storage root: " + key);
        }
        return path;
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best effort: a stray temp file is harmless.
        }
    }
}
