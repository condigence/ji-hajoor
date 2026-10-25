package com.jihajoor.verification.storage;

import java.io.InputStream;

/**
 * Stored document bytes being read back. The caller owns {@code stream} and must close it; it is
 * streamed straight to the HTTP response rather than buffered.
 *
 * @param stream the bytes
 * @param length their count, or {@code -1} when the store did not say
 */
public record DocumentContent(InputStream stream, long length) {
}
