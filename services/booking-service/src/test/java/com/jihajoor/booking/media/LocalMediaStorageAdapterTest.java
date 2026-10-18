package com.jihajoor.booking.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jihajoor.booking.domain.MediaStorageKind;

/**
 * The local-disk store: bytes land under the root at the given key and read back unchanged; a key
 * with nothing stored reads as empty (as for rows from before bytes were kept); a key cannot
 * escape the root.
 */
class LocalMediaStorageAdapterTest {

    @TempDir
    Path root;

    @Test
    void storesTheBytesUnderTheKeyAndReadsThemBack() throws Exception {
        LocalMediaStorageAdapter adapter = new LocalMediaStorageAdapter(root);
        MediaFile file = new MediaFile("photo.jpg", "image/jpeg", 3, new byte[]{1, 2, 3});

        String stored = adapter.put("bookings/b1/m1.jpg", file);

        assertThat(adapter.kind()).isEqualTo(MediaStorageKind.LOCAL);
        assertThat(stored).isEqualTo("bookings/b1/m1.jpg");
        assertThat(Files.readAllBytes(root.resolve("bookings/b1/m1.jpg"))).containsExactly(1, 2, 3);
        MediaContent content = adapter.get(stored).orElseThrow();
        assertThat(content.length()).isEqualTo(3);
        try (InputStream in = content.stream()) {
            assertThat(in.readAllBytes()).containsExactly(1, 2, 3);
        }
        // Written via a temporary sibling, which does not linger.
        try (var files = Files.list(root.resolve("bookings/b1"))) {
            assertThat(files).containsExactly(root.resolve("bookings/b1/m1.jpg"));
        }
    }

    @Test
    void aKeyWithNothingStoredReadsAsEmpty() {
        LocalMediaStorageAdapter adapter = new LocalMediaStorageAdapter(root);

        assertThat(adapter.get("bookings/b1/media/legacy")).isEmpty();
    }

    @Test
    void aKeyCannotEscapeTheRoot() {
        LocalMediaStorageAdapter adapter = new LocalMediaStorageAdapter(root.resolve("media"));
        MediaFile file = new MediaFile("x", "image/jpeg", 1, new byte[]{1});

        assertThatThrownBy(() -> adapter.put("../outside.jpg", file))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.get("bookings/../../outside.jpg"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
