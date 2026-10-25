package com.jihajoor.verification.storage;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jihajoor.verification.config.DocumentStorageProperties;
import com.jihajoor.verification.domain.DocumentStorageKind;
import com.jihajoor.verification.domain.DocumentType;

/**
 * The verification service's document store: writes each upload to the one adapter chosen at
 * startup, and reads each stored row back from the adapter its {@link DocumentStorageKind} names.
 * The same model as booking-service's {@code MediaStorage}.
 *
 * <h2>Choosing where to write ({@link #create})</h2>
 * "S3 if it is available, otherwise local disk", decided once per process:
 * <table>
 *   <caption>Write store by mode</caption>
 *   <tr><th>{@code provider}</th><th>bucket</th><th>reachable at startup</th><th>writes to</th></tr>
 *   <tr><td>auto</td><td>not set</td><td>-</td><td>local (INFO)</td></tr>
 *   <tr><td>auto</td><td>set</td><td>yes</td><td>S3</td></tr>
 *   <tr><td>auto</td><td>set</td><td>no</td><td>local (WARN)</td></tr>
 *   <tr><td>s3</td><td>not set</td><td>-</td><td>startup fails</td></tr>
 *   <tr><td>s3</td><td>set</td><td>no</td><td>startup fails</td></tr>
 *   <tr><td>local</td><td>any</td><td>not checked</td><td>local</td></tr>
 * </table>
 * Reachability is one {@code HeadBucket}, bounded by {@code s3.startup-check-timeout}. A failed
 * check in auto mode degrades rather than failing startup: providers can keep submitting, at the
 * cost of their documents living on this instance's disk, which the WARN says. A restart with S3
 * reachable returns the process to S3.
 *
 * <h2>Why a failure is not redirected per request</h2>
 * Once S3 is chosen, every upload goes to S3 and an S3 failure fails that submission (a retriable
 * 503). Falling back to local disk per request would accept a document only the accepting replica
 * can serve, invisible to the admin reviewing it through any other replica.
 *
 * <h2>Reading back</h2>
 * Each {@code verification_document} row records its kind, so S3 documents and the documents
 * stored on disk before S3 was turned on are both read from where they are. The S3 adapter exists
 * whenever a bucket and a region are configured, in every mode, so an instance that fell back to
 * local (or is forced local) still serves the documents other instances put in S3. Rows from before
 * any bytes were kept have no kind and are never looked up ({@code VerificationService}).
 *
 * <h2>Keys</h2>
 * {@code providers/{providerId}/{TYPE}/{documentId}.{ext}}, the extension taken from the content
 * type, else the file name, so objects are browsable and per-provider cleanup is one prefix. The
 * S3 adapter prepends its configured prefix.
 */
public class DocumentStorage implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DocumentStorage.class);

    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", "jpg",
            "image/jpg", "jpg",
            "image/png", "png",
            "image/webp", "webp",
            "image/heic", "heic",
            "image/heif", "heif",
            "application/pdf", "pdf");

    private final DocumentStoragePort writer;
    private final Map<DocumentStorageKind, DocumentStoragePort> readers = new EnumMap<>(DocumentStorageKind.class);

    /**
     * @param writer  the adapter new uploads go to; also a reader
     * @param readers further adapters stored rows may be read from (null entries are ignored)
     */
    public DocumentStorage(DocumentStoragePort writer, DocumentStoragePort... readers) {
        this.writer = writer;
        this.readers.put(writer.kind(), writer);
        for (DocumentStoragePort reader : readers) {
            if (reader != null) {
                this.readers.putIfAbsent(reader.kind(), reader);
            }
        }
    }

    /**
     * Builds the store from configuration, choosing the write adapter as the class documentation
     * tabulates.
     *
     * @param s3Factory builds the S3 adapter from its settings ({@link S3DocumentStorageAdapter#create}
     *                  in production; tests pass one over a mocked client)
     * @throws IllegalStateException when {@code provider=s3} cannot be honoured, or a setting is invalid
     */
    public static DocumentStorage create(DocumentStorageProperties properties,
                                         Function<DocumentStorageProperties.S3, S3DocumentStorageAdapter> s3Factory) {
        DocumentStorageProperties.Mode mode = properties.getProvider() == null
                ? DocumentStorageProperties.Mode.AUTO
                : properties.getProvider();
        DocumentStorageProperties.S3 s3Config = properties.getS3();
        LocalDocumentStorageAdapter local = new LocalDocumentStorageAdapter(properties.getLocal().directoryPath());

        S3DocumentStorageAdapter s3 = null;
        if (s3Config.isConfigured()) {
            if (s3Config.effectiveRegion() == null) {
                String problem = "jihajoor.verification.document-storage.s3.bucket is set to '" + s3Config.getBucket()
                        + "' but no region is configured (DOCUMENTS_S3_REGION or AWS_REGION)";
                if (mode == DocumentStorageProperties.Mode.S3) {
                    throw new IllegalStateException(problem);
                }
                log.warn("{}; S3 document storage is disabled and verification documents are stored on "
                        + "local disk at {}", problem, local.root());
            } else {
                s3 = s3Factory.apply(s3Config);
            }
        }

        DocumentStoragePort writer = switch (mode) {
            case LOCAL -> {
                log.info("Document storage: local disk at {} (jihajoor.verification.document-storage.provider=local){}",
                        local.root(), s3 == null ? "" : "; S3-stored documents are still read from bucket " + s3.bucket());
                yield local;
            }
            case S3 -> {
                if (s3 == null) {
                    throw new IllegalStateException("jihajoor.verification.document-storage.provider=s3 but no "
                            + "bucket is configured (DOCUMENTS_BUCKET)");
                }
                try {
                    s3.verifyReachable(s3Config.getStartupCheckTimeout());
                } catch (RuntimeException e) {
                    s3.close();
                    throw new IllegalStateException("jihajoor.verification.document-storage.provider=s3 but bucket '"
                            + s3.bucket() + "' is not reachable: " + e.getMessage(), e);
                }
                log.info("Document storage: S3 bucket {} (jihajoor.verification.document-storage.provider=s3)",
                        s3.bucket());
                yield s3;
            }
            case AUTO -> {
                if (s3 == null) {
                    log.info("Document storage: local disk at {} (no S3 bucket configured)", local.root());
                    yield local;
                }
                try {
                    s3.verifyReachable(s3Config.getStartupCheckTimeout());
                    log.info("Document storage: S3 bucket {}", s3.bucket());
                    yield s3;
                } catch (RuntimeException e) {
                    log.warn("Document storage: S3 bucket '{}' is not reachable ({}: {}); this instance stores new "
                                    + "verification documents on its local disk at {} until it is restarted with S3 "
                                    + "reachable. Documents stored there are not visible to other instances. Set "
                                    + "DOCUMENT_STORAGE_PROVIDER=s3 to fail startup instead.",
                            s3.bucket(), e.getClass().getSimpleName(), e.getMessage(), local.root());
                    yield local;
                }
            }
        };
        return new DocumentStorage(writer, local, s3);
    }

    /** The kind of store new uploads are written to. */
    public DocumentStorageKind writeKind() {
        return writer.kind();
    }

    /**
     * Writes {@code file} as document {@code documentId} of provider {@code providerId}.
     *
     * @throws DocumentStorageException when the write store cannot take it; never redirected elsewhere
     */
    public StoredDocument store(UUID providerId, DocumentType type, UUID documentId, DocumentFile file) {
        String key = "providers/" + providerId + "/" + type.name() + "/" + documentId + "." + extension(file);
        return new StoredDocument(writer.kind(), writer.put(key, file));
    }

    /**
     * The bytes of a stored row.
     *
     * @return empty when the store has nothing under {@code key}
     * @throws DocumentStorageException when the store cannot be read, or this instance has no adapter
     *         for {@code kind} (S3 rows on an instance without S3 settings)
     */
    public Optional<DocumentContent> load(DocumentStorageKind kind, String key) {
        DocumentStoragePort reader = readers.get(kind);
        if (reader == null) {
            throw new DocumentStorageException("Document is stored in " + kind
                    + " but this instance has no " + kind + " storage configured");
        }
        return reader.get(key);
    }

    @Override
    public void close() {
        for (DocumentStoragePort reader : readers.values()) {
            if (reader instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception e) {
                    log.debug("Closing document storage adapter {} failed: {}", reader.kind(), e.getMessage());
                }
            }
        }
    }

    /** File extension for the upload: from its content type, else its file name, else {@code bin}. */
    static String extension(DocumentFile file) {
        String contentType = file.contentType() == null ? "" : file.contentType().strip().toLowerCase(Locale.ROOT);
        int semicolon = contentType.indexOf(';');
        if (semicolon >= 0) {
            contentType = contentType.substring(0, semicolon).strip();
        }
        String known = EXTENSIONS.get(contentType);
        if (known != null) {
            return known;
        }
        String name = file.originalFilename();
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (ext.matches("[a-z0-9]{1,8}")) {
                    return ext;
                }
            }
        }
        return "bin";
    }
}
