package com.jihajoor.booking.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jihajoor.booking.domain.MediaStorageKind;

/**
 * {@link MediaStoragePort} on the local filesystem, under one root directory
 * ({@code jihajoor.media-storage.local.directory}). Used in development, in tests, and wherever S3
 * is not configured or was unreachable at startup ({@link MediaStorage}).
 *
 * <p>A file written here is visible only to the instances that see the same directory: on a
 * multi-replica deployment without a shared volume, a photo uploaded through one replica is a 404
 * on the others, and an ephemeral container disk loses it on redeploy. That is the trade-off of the
 * fallback, which is why {@code MediaStorage} warns whenever it chooses this adapter over a
 * configured bucket.
 *
 * <p>Files are written to a temporary sibling and moved into place, so a reader never sees half a
 * file. Keys come from {@code MediaStorage} and the database, never from a request, but are still
 * confined to the root: a key that would resolve outside it is refused.
 *
 * <p>Before the {@code storage} column existed this adapter kept no bytes at all, only derived a
 * key; rows from that time read back as "not stored" ({@link #get} answers empty).
 */
public class LocalMediaStorageAdapter implements MediaStoragePort {

    private static final Logger log = LoggerFactory.getLogger(LocalMediaStorageAdapter.class);

    private final Path root;

    public LocalMediaStorageAdapter(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public MediaStorageKind kind() {
        return MediaStorageKind.LOCAL;
    }

    /** The root directory files are written under. */
    public Path root() {
        return root;
    }

    @Override
    public String put(String key, MediaFile file) {
        Path target = resolve(key);
        Path temp = null;
        try {
            Files.createDirectories(target.getParent());
            temp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            Files.write(temp, file.bytes());
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new MediaStorageException("Could not write media to local storage at " + target, e);
        }
        log.debug("Local media storage: stored {} bytes contentType={} at {}",
                file.bytes().length, file.contentType(), target);
        return key;
    }

    @Override
    public Optional<MediaContent> get(String storedKey) {
        Path file = resolve(storedKey);
        try {
            long size = Files.size(file);
            return Optional.of(new MediaContent(Files.newInputStream(file), size));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new MediaStorageException("Could not read media from local storage at " + file, e);
        }
    }

    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root) || path.equals(root)) {
            throw new IllegalArgumentException("Media key escapes the storage root: " + key);
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
