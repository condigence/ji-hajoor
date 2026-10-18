package com.jihajoor.booking.media;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jihajoor.booking.config.MediaStorageProperties;
import com.jihajoor.booking.domain.MediaStorageKind;

/**
 * The booking service's media store: writes each upload to the one adapter chosen at startup, and
 * reads each stored row back from the adapter its {@link MediaStorageKind} names.
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
 * check in auto mode degrades rather than failing startup: the service keeps taking photos, at the
 * cost of them living on this instance's disk, which the WARN says. A restart with S3 reachable
 * returns the process to S3.
 *
 * <h2>Why a failure is not redirected per request</h2>
 * Once S3 is chosen, every upload goes to S3 and an S3 failure fails that upload (a retriable 503
 * via {@code MediaService}). Falling back to local disk per request would accept a photo only the
 * accepting replica can serve, invisible to the other replicas and to the customer reading the job
 * through them, and gone on the next redeploy; the client would have no reason to retry. Failing
 * loudly keeps every acknowledged photo readable everywhere.
 *
 * <h2>Reading back</h2>
 * Each {@code job_media} row records its kind, so S3 photos and the photos stored on disk before S3
 * was turned on are both read from where they are. The S3 adapter exists whenever a bucket and a
 * region are configured, in every mode, so an instance that fell back to local (or is forced local)
 * still serves the photos other instances put in S3.
 *
 * <h2>Keys</h2>
 * {@code bookings/{bookingId}/{mediaId}.{ext}}, the extension taken from the content type (the
 * service accepts JPEG, PNG, MP4 and MOV), so objects are browsable and per-booking cleanup is one
 * prefix. The S3 adapter prepends its configured prefix.
 */
public class MediaStorage implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MediaStorage.class);

    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "video/mp4", "mp4",
            "video/quicktime", "mov");

    private final MediaStoragePort writer;
    private final Map<MediaStorageKind, MediaStoragePort> readers = new EnumMap<>(MediaStorageKind.class);

    /**
     * @param writer  the adapter new uploads go to; also a reader
     * @param readers further adapters stored rows may be read from (null entries are ignored)
     */
    public MediaStorage(MediaStoragePort writer, MediaStoragePort... readers) {
        this.writer = writer;
        this.readers.put(writer.kind(), writer);
        for (MediaStoragePort reader : readers) {
            if (reader != null) {
                this.readers.putIfAbsent(reader.kind(), reader);
            }
        }
    }

    /**
     * Builds the store from configuration, choosing the write adapter as the class documentation
     * tabulates.
     *
     * @param s3Factory builds the S3 adapter from its settings ({@link S3MediaStorageAdapter#create}
     *                  in production; tests pass one over a mocked client)
     * @throws IllegalStateException when {@code provider=s3} cannot be honoured, or a setting is invalid
     */
    public static MediaStorage create(MediaStorageProperties properties,
                                      Function<MediaStorageProperties.S3, S3MediaStorageAdapter> s3Factory) {
        MediaStorageProperties.Mode mode = properties.getProvider() == null
                ? MediaStorageProperties.Mode.AUTO
                : properties.getProvider();
        MediaStorageProperties.S3 s3Config = properties.getS3();
        LocalMediaStorageAdapter local = new LocalMediaStorageAdapter(properties.getLocal().directoryPath());

        S3MediaStorageAdapter s3 = null;
        if (s3Config.isConfigured()) {
            if (s3Config.effectiveRegion() == null) {
                String problem = "jihajoor.media-storage.s3.bucket is set to '" + s3Config.getBucket()
                        + "' but no region is configured (MEDIA_S3_REGION or AWS_REGION)";
                if (mode == MediaStorageProperties.Mode.S3) {
                    throw new IllegalStateException(problem);
                }
                log.warn("{}; S3 media storage is disabled and photos are stored on local disk at {}",
                        problem, local.root());
            } else {
                s3 = s3Factory.apply(s3Config);
            }
        }

        MediaStoragePort writer = switch (mode) {
            case LOCAL -> {
                log.info("Media storage: local disk at {} (jihajoor.media-storage.provider=local){}",
                        local.root(), s3 == null ? "" : "; S3-stored media is still read from bucket " + s3.bucket());
                yield local;
            }
            case S3 -> {
                if (s3 == null) {
                    throw new IllegalStateException(
                            "jihajoor.media-storage.provider=s3 but no bucket is configured (MEDIA_BUCKET)");
                }
                try {
                    s3.verifyReachable(s3Config.getStartupCheckTimeout());
                } catch (RuntimeException e) {
                    s3.close();
                    throw new IllegalStateException("jihajoor.media-storage.provider=s3 but bucket '"
                            + s3.bucket() + "' is not reachable: " + e.getMessage(), e);
                }
                log.info("Media storage: S3 bucket {} (jihajoor.media-storage.provider=s3)", s3.bucket());
                yield s3;
            }
            case AUTO -> {
                if (s3 == null) {
                    log.info("Media storage: local disk at {} (no S3 bucket configured)", local.root());
                    yield local;
                }
                try {
                    s3.verifyReachable(s3Config.getStartupCheckTimeout());
                    log.info("Media storage: S3 bucket {}", s3.bucket());
                    yield s3;
                } catch (RuntimeException e) {
                    log.warn("Media storage: S3 bucket '{}' is not reachable ({}: {}); this instance stores new "
                                    + "photos on its local disk at {} until it is restarted with S3 reachable. "
                                    + "Photos stored there are not visible to other instances. Set "
                                    + "jihajoor.media-storage.provider=s3 to fail startup instead.",
                            s3.bucket(), e.getClass().getSimpleName(), e.getMessage(), local.root());
                    yield local;
                }
            }
        };
        return new MediaStorage(writer, local, s3);
    }

    /** The kind of store new uploads are written to. */
    public MediaStorageKind writeKind() {
        return writer.kind();
    }

    /**
     * Writes {@code file} as media {@code mediaId} of booking {@code bookingId}.
     *
     * @throws MediaStorageException when the write store cannot take it; never redirected elsewhere
     */
    public StoredMedia store(UUID bookingId, UUID mediaId, MediaFile file) {
        String key = "bookings/" + bookingId + "/" + mediaId + "." + extension(file);
        return new StoredMedia(writer.kind(), writer.put(key, file));
    }

    /**
     * The bytes of a stored row.
     *
     * @return empty when the store has nothing under {@code key}
     * @throws MediaStorageException when the store cannot be read, or this instance has no adapter
     *         for {@code kind} (S3 rows on an instance without S3 settings)
     */
    public Optional<MediaContent> load(MediaStorageKind kind, String key) {
        MediaStoragePort reader = readers.get(kind == null ? MediaStorageKind.LOCAL : kind);
        if (reader == null) {
            throw new MediaStorageException("Media is stored in " + kind
                    + " but this instance has no " + kind + " storage configured");
        }
        return reader.get(key);
    }

    @Override
    public void close() {
        for (MediaStoragePort reader : readers.values()) {
            if (reader instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception e) {
                    log.debug("Closing media storage adapter {} failed: {}", reader.kind(), e.getMessage());
                }
            }
        }
    }

    /** File extension for the upload: from its content type, else its file name, else {@code bin}. */
    static String extension(MediaFile file) {
        String contentType = file.contentType() == null ? "" : file.contentType().toLowerCase(Locale.ROOT);
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
