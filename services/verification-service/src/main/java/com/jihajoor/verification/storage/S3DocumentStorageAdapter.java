package com.jihajoor.verification.storage;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jihajoor.verification.config.DocumentStorageProperties;
import com.jihajoor.verification.domain.DocumentStorageKind;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/**
 * {@link DocumentStoragePort} on Amazon S3 or an S3-compatible store (MinIO, LocalStack), through
 * the AWS SDK for Java v2's synchronous {@link S3Client}. The same design as booking-service's
 * {@code S3MediaStorageAdapter}.
 *
 * <h2>Objects</h2>
 * One object per document at {@code <prefix>providers/{providerId}/{TYPE}/{documentId}.{ext}},
 * with the upload's {@code Content-Type} stored on the object. Server-side encryption follows
 * {@code jihajoor.verification.document-storage.s3.server-side-encryption}: blank leaves it to the
 * bucket's default (on AWS at least SSE-S3; the Terraform documents bucket defaults to SSE-KMS),
 * {@code AES256} asks for SSE-S3 and {@code aws:kms} for SSE-KMS, with the given key or the
 * account's {@code aws/s3} key (Requirement 5.3). The full key, prefix included, is what the row
 * records, so changing the prefix later does not orphan existing documents.
 *
 * <h2>Credentials and endpoint</h2>
 * The SDK's default credentials chain (environment variables, web identity / IRSA on EKS, container
 * and instance roles, the shared profile) unless both static keys are configured, which is what an
 * S3-compatible store or a shared-JVM deployable with per-service variables needs. An endpoint
 * override switches checksums to "when required", since S3-compatible stores do not all accept the
 * SDK's default CRC trailers. The role needs {@code s3:PutObject} and {@code s3:GetObject} on the
 * prefix, {@code s3:ListBucket} on the bucket (what {@code HeadBucket} is authorised by), and
 * {@code kms:GenerateDataKey}/{@code kms:Decrypt} on the key when SSE-KMS is used.
 *
 * <h2>Failures</h2>
 * Any SDK failure other than "no such key" is a {@link DocumentStorageException}, which the upload
 * path answers with a retriable 503. It is deliberately not caught and redirected to local disk:
 * once a process writes to S3, a document that quietly landed on one replica's disk instead would
 * be missing for every other replica (and the admin reviewing it) and lost on the next redeploy.
 */
public class S3DocumentStorageAdapter implements DocumentStoragePort, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(S3DocumentStorageAdapter.class);

    private final S3Client s3;
    private final String bucket;
    private final String prefix;
    private final ServerSideEncryption serverSideEncryption;
    private final String kmsKeyId;

    /**
     * @param s3     the client; closed by {@link #close}
     * @param config bucket, prefix and encryption settings
     * @throws IllegalStateException when the encryption setting is not one S3 knows
     */
    public S3DocumentStorageAdapter(S3Client s3, DocumentStorageProperties.S3 config) {
        this.s3 = s3;
        this.bucket = config.getBucket().strip();
        this.prefix = config.normalizedPrefix();
        this.serverSideEncryption = parseServerSideEncryption(config.getServerSideEncryption());
        this.kmsKeyId = blankToNull(config.getKmsKeyId());
        if (kmsKeyId != null && serverSideEncryption != ServerSideEncryption.AWS_KMS) {
            throw new IllegalStateException("jihajoor.verification.document-storage.s3.kms-key-id is set but "
                    + "server-side-encryption is not aws:kms");
        }
    }

    /** An adapter over a client built from {@code config} (region, endpoint, credentials). */
    public static S3DocumentStorageAdapter create(DocumentStorageProperties.S3 config) {
        S3Client client = buildClient(config);
        try {
            return new S3DocumentStorageAdapter(client, config);
        } catch (RuntimeException e) {
            client.close();
            throw e;
        }
    }

    static S3Client buildClient(DocumentStorageProperties.S3 config) {
        String region = config.effectiveRegion();
        if (region == null) {
            throw new IllegalStateException(
                    "S3 document storage needs a region (jihajoor.verification.document-storage.s3.region)");
        }
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(credentials(config))
                .forcePathStyle(config.isPathStyleAccess());
        String endpoint = blankToNull(config.getEndpoint());
        if (endpoint != null) {
            builder.endpointOverride(URI.create(endpoint))
                    .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                    .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
        }
        return builder.build();
    }

    private static AwsCredentialsProvider credentials(DocumentStorageProperties.S3 config) {
        if (config.hasStaticCredentials()) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(
                    config.getAccessKeyId().strip(), config.getSecretAccessKey().strip()));
        }
        return DefaultCredentialsProvider.builder().build();
    }

    @Override
    public DocumentStorageKind kind() {
        return DocumentStorageKind.S3;
    }

    /** The bucket objects are written to, for logs. */
    public String bucket() {
        return bucket;
    }

    /**
     * Confirms the bucket exists and these credentials may use it ({@code HeadBucket}), giving up
     * after {@code timeout} including the SDK's retries so an unreachable endpoint cannot hold up
     * startup for long.
     *
     * @throws SdkException when it does not answer, or answers with an error
     */
    public void verifyReachable(Duration timeout) {
        s3.headBucket(HeadBucketRequest.builder()
                .bucket(bucket)
                .overrideConfiguration(o -> o.apiCallTimeout(timeout))
                .build());
    }

    @Override
    public String put(String key, DocumentFile file) {
        String objectKey = prefix + key;
        PutObjectRequest.Builder request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .contentType(file.contentType())
                .contentLength((long) file.bytes().length);
        if (serverSideEncryption != null) {
            request.serverSideEncryption(serverSideEncryption);
        }
        if (kmsKeyId != null) {
            request.ssekmsKeyId(kmsKeyId);
        }
        try {
            s3.putObject(request.build(), RequestBody.fromBytes(file.bytes()));
        } catch (SdkException e) {
            throw new DocumentStorageException(
                    "Could not store document in s3://" + bucket + "/" + objectKey + ": " + e.getMessage(), e);
        }
        log.debug("S3 document storage: stored {} bytes contentType={} at s3://{}/{}",
                file.bytes().length, file.contentType(), bucket, objectKey);
        return objectKey;
    }

    @Override
    public Optional<DocumentContent> get(String storedKey) {
        try {
            ResponseInputStream<GetObjectResponse> object = s3.getObject(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(storedKey)
                    .build());
            Long length = object.response().contentLength();
            return Optional.of(new DocumentContent(object, length == null ? -1 : length));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw new DocumentStorageException(
                    "Could not read document from s3://" + bucket + "/" + storedKey + ": " + e.getMessage(), e);
        } catch (SdkException e) {
            throw new DocumentStorageException(
                    "Could not read document from s3://" + bucket + "/" + storedKey + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        s3.close();
    }

    private static ServerSideEncryption parseServerSideEncryption(String value) {
        String v = blankToNull(value);
        if (v == null || "none".equalsIgnoreCase(v)) {
            return null;
        }
        ServerSideEncryption sse = ServerSideEncryption.fromValue(v);
        if (sse == null || sse == ServerSideEncryption.UNKNOWN_TO_SDK_VERSION) {
            throw new IllegalStateException("Unsupported jihajoor.verification.document-storage.s3."
                    + "server-side-encryption '" + v + "'; use AES256, aws:kms, or leave it blank");
        }
        return sse;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
