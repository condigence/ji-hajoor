package com.jihajoor.booking.media;

import com.jihajoor.booking.domain.MediaStorageKind;

/**
 * Where an upload was written: the two facts a {@code job_media} row needs to read it back.
 *
 * @param kind the store holding the bytes
 * @param key  the key within that store
 */
public record StoredMedia(MediaStorageKind kind, String key) {
}
