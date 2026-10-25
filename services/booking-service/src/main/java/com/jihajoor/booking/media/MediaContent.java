package com.jihajoor.booking.media;

import java.io.InputStream;

/**
 * Stored media bytes being read back. The caller owns {@code stream} and must close it; it is
 * streamed straight to the HTTP response rather than buffered, since a video may be 50 MB.
 *
 * @param stream the bytes
 * @param length their count, or {@code -1} when the store did not say
 */
public record MediaContent(InputStream stream, long length) {
}
