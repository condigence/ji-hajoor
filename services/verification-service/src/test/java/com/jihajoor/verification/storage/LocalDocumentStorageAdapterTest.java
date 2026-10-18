package com.jihajoor.verification.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jihajoor.verification.domain.DocumentStorageKind;

/**
 * The local-disk store: bytes land under the root at the given key and read back unchanged; a key
 * with nothing stored reads as empty; a key cannot escape the root.
 */
class LocalDocumentStorageAdapterTest {

    @TempDir
    Path root;

    @Test
    void storesTheBytesUnderTheKeyAndReadsThemBack() throws Exception {
        LocalDocumentStorageAdapter adapter = new LocalDocumentStorageAdapter(root);
        DocumentFile file = new DocumentFile("id.pdf", "application/pdf", new byte[]{1, 2, 3});

        String stored = adapter.put("providers/p1/GOVERNMENT_ID/d1.pdf", file);

        assertThat(adapter.kind()).isEqualTo(DocumentStorageKind.LOCAL);
        assertThat(stored).isEqualTo("providers/p1/GOVERNMENT_ID/d1.pdf");
        assertThat(Files.readAllBytes(root.resolve("providers/p1/GOVERNMENT_ID/d1.pdf"))).containsExactly(1, 2, 3);
        DocumentContent content = adapter.get(stored).orElseThrow();
        assertThat(content.length()).isEqualTo(3);
        try (InputStream in = content.stream()) {
            assertThat(in.readAllBytes()).containsExactly(1, 2, 3);
        }
        // Written via a temporary sibling, which does not linger.
        try (var files = Files.list(root.resolve("providers/p1/GOVERNMENT_ID"))) {
            assertThat(files).containsExactly(root.resolve("providers/p1/GOVERNMENT_ID/d1.pdf"));
        }
    }

    @Test
    void aKeyWithNothingStoredReadsAsEmpty() {
        LocalDocumentStorageAdapter adapter = new LocalDocumentStorageAdapter(root);

        assertThat(adapter.get("providers/p1/GOVERNMENT_ID/missing.pdf")).isEmpty();
    }

    @Test
    void aKeyCannotEscapeTheRoot() {
        LocalDocumentStorageAdapter adapter = new LocalDocumentStorageAdapter(root.resolve("documents"));
        DocumentFile file = new DocumentFile("x", "image/jpeg", new byte[]{1});

        assertThatThrownBy(() -> adapter.put("../outside.jpg", file))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.get("providers/../../outside.jpg"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
