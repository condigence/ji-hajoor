package com.jihajoor.booking.media;

import java.util.Optional;

import com.jihajoor.booking.domain.MediaStorageKind;

/**
 * Port abstracting the object store booking media is kept in (Requirement 7.2): S3 in production
 * ({@link S3MediaStorageAdapter}), the local disk otherwise ({@link LocalMediaStorageAdapter}).
 *
 * <p>An adapter only moves bytes under a key it is given. Choosing the key, choosing which adapter
 * to write to, and finding the adapter a stored row must be read from are {@link MediaStorage}'s;
 * the business logic talks to that, not to an adapter.
 */
public interface MediaStoragePort {

    /** The kind recorded on a {@code job_media} row written through this adapter. */
    MediaStorageKind kind();

    /**
     * Stores {@code file} under {@code key} ({@code bookings/{bookingId}/{mediaId}.{ext}}).
     *
     * @return the key to persist and later pass to {@link #get}: {@code key} itself, or with the
     *         adapter's own prefix applied
     * @throws MediaStorageException when the store cannot take the file right now
     */
    String put(String key, MediaFile file);

    /**
     * The bytes stored under {@code storedKey}.
     *
     * @return empty when nothing is stored under that key
     * @throws MediaStorageException when the store cannot be read right now
     */
    Optional<MediaContent> get(String storedKey);
}
