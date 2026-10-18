package com.jihajoor.booking.config;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where booking media (customer uploads, before/after job photos) is stored, bound from
 * {@code jihajoor.media-storage.*}. How these settings pick a store is {@code MediaStorage}'s
 * business; in short: S3 when a bucket is configured and reachable, local disk otherwise.
 */
@ConfigurationProperties(prefix = "jihajoor.media-storage")
public class MediaStorageProperties {

    /**
     * Which store new media is written to.
     *
     * <ul>
     *   <li>{@link #AUTO} (default): S3 when {@code s3.bucket} is set and the bucket answers a
     *       {@code HeadBucket} at startup; local disk otherwise, with a WARN when a configured
     *       bucket could not be reached.</li>
     *   <li>{@link #S3}: S3 or nothing. A missing bucket or an unreachable one fails startup,
     *       for deployments where a photo on one replica's disk would be a defect.</li>
     *   <li>{@link #LOCAL}: always local disk; S3 is never written (but S3-stored rows are still
     *       read back if a bucket is configured).</li>
     * </ul>
     */
    public enum Mode {
        AUTO, S3, LOCAL
    }

    /**
     * The write mode ({@code auto | s3 | local}, any case). The property keeps the name it had
     * when it selected between the two adapters, so deployments that already set
     * {@code MEDIA_STORAGE_PROVIDER} keep their meaning.
     */
    private Mode provider = Mode.AUTO;

    private final Local local = new Local();

    private final S3 s3 = new S3();

    public Mode getProvider() {
        return provider;
    }

    public void setProvider(Mode provider) {
        this.provider = provider;
    }

    public Local getLocal() {
        return local;
    }

    public S3 getS3() {
        return s3;
    }

    /** The local-disk store. */
    public static class Local {

        /**
         * Root directory media files are written under ({@code <directory>/bookings/...}). Local to
         * the instance unless it is a shared volume.
         */
        private String directory = Path.of(System.getProperty("java.io.tmpdir"), "jihajoor-media").toString();

        public String getDirectory() {
            return directory;
        }

        public void setDirectory(String directory) {
            this.directory = directory;
        }

        /** {@link #directory} as an absolute, normalised path. */
        public Path directoryPath() {
            return Path.of(directory).toAbsolutePath().normalize();
        }
    }

    /**
     * The S3 store. Credentials follow the AWS SDK default chain (environment, web identity /
     * IRSA, container and instance roles, profile) unless both static keys are set. The static
     * keys exist for S3-compatible stores (MinIO, LocalStack) and for a JVM hosting several
     * services: a per-service {@code BOOKING_SERVICE__...} variable reaches Spring's environment
     * only, never the SDK's own {@code AWS_ACCESS_KEY_ID} lookup.
     */
    public static class S3 {

        /** Bucket name. Blank means S3 is not configured. */
        private String bucket = "";

        /** AWS region, e.g. {@code ap-south-1}. Required unless {@link #endpoint} is set. */
        private String region = "";

        /**
         * Key prefix inside the bucket, e.g. {@code prod/}; a trailing slash is added when
         * missing. Objects are {@code <prefix>bookings/{bookingId}/{mediaId}.{ext}}.
         */
        private String prefix = "";

        /**
         * Endpoint override for an S3-compatible store, e.g. {@code http://minio:9000}. Blank for
         * AWS. When set and {@link #region} is blank, {@code us-east-1} is used for signing.
         */
        private String endpoint = "";

        /** Path-style addressing ({@code endpoint/bucket/key}); MinIO and LocalStack need it. */
        private boolean pathStyleAccess = false;

        /**
         * Server-side encryption requested per object: blank (the bucket's default encryption,
         * which on AWS is at least SSE-S3), {@code AES256} (SSE-S3) or {@code aws:kms} (SSE-KMS).
         */
        private String serverSideEncryption = "";

        /** KMS key id or ARN for {@code aws:kms}; blank uses the account's {@code aws/s3} key. */
        private String kmsKeyId = "";

        /** Static access key id; used only together with {@link #secretAccessKey}. */
        private String accessKeyId = "";

        /** Static secret access key; used only together with {@link #accessKeyId}. */
        private String secretAccessKey = "";

        /** Upper bound on the startup {@code HeadBucket} reachability check, retries included. */
        private Duration startupCheckTimeout = Duration.ofSeconds(10);

        /** Whether a bucket is set, i.e. whether S3 is configured at all. */
        public boolean isConfigured() {
            return hasText(bucket);
        }

        /** The region requests are signed for, or {@code null} when there is none to use. */
        public String effectiveRegion() {
            if (hasText(region)) {
                return region.strip();
            }
            return hasText(endpoint) ? "us-east-1" : null;
        }

        /** {@link #prefix} normalised: no leading slash, a trailing one when non-empty. */
        public String normalizedPrefix() {
            String p = prefix == null ? "" : prefix.strip();
            while (p.startsWith("/")) {
                p = p.substring(1);
            }
            return p.isEmpty() || p.endsWith("/") ? p : p + "/";
        }

        public boolean hasStaticCredentials() {
            return hasText(accessKeyId) && hasText(secretAccessKey);
        }

        static boolean hasText(String value) {
            return value != null && !value.isBlank();
        }

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public String getPrefix() {
            return prefix;
        }

        public void setPrefix(String prefix) {
            this.prefix = prefix;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public boolean isPathStyleAccess() {
            return pathStyleAccess;
        }

        public void setPathStyleAccess(boolean pathStyleAccess) {
            this.pathStyleAccess = pathStyleAccess;
        }

        public String getServerSideEncryption() {
            return serverSideEncryption;
        }

        public void setServerSideEncryption(String serverSideEncryption) {
            this.serverSideEncryption = serverSideEncryption;
        }

        public String getKmsKeyId() {
            return kmsKeyId;
        }

        public void setKmsKeyId(String kmsKeyId) {
            this.kmsKeyId = kmsKeyId;
        }

        public String getAccessKeyId() {
            return accessKeyId;
        }

        public void setAccessKeyId(String accessKeyId) {
            this.accessKeyId = accessKeyId;
        }

        public String getSecretAccessKey() {
            return secretAccessKey;
        }

        public void setSecretAccessKey(String secretAccessKey) {
            this.secretAccessKey = secretAccessKey;
        }

        public Duration getStartupCheckTimeout() {
            return startupCheckTimeout;
        }

        public void setStartupCheckTimeout(Duration startupCheckTimeout) {
            this.startupCheckTimeout = startupCheckTimeout;
        }
    }
}
