package com.jihajoor.verification.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import com.jihajoor.verification.config.DocumentStorageProperties;
import com.jihajoor.verification.config.DocumentStorageProperties.Mode;
import com.jihajoor.verification.domain.DocumentStorageKind;
import com.jihajoor.verification.domain.DocumentType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * "S3 if it is available, otherwise local": which store {@link DocumentStorage#create} writes to
 * for each mode and configuration, that an unreachable bucket in auto mode degrades to local with
 * a WARN rather than failing startup, that the forced modes are honoured, and that rows of either
 * kind are read back from where they were written, whatever this process writes to.
 */
class DocumentStorageTest {

    private static final UUID PROVIDER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DOCUMENT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final DocumentFile ID_SCAN = new DocumentFile("aadhaar.jpg", "image/jpeg", new byte[]{1, 2, 3});

    @TempDir
    Path dir;

    private DocumentStorageProperties properties;
    private S3Client s3;
    private final AtomicInteger s3Built = new AtomicInteger();
    private Function<DocumentStorageProperties.S3, S3DocumentStorageAdapter> s3Factory;
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void setUp() {
        properties = new DocumentStorageProperties();
        properties.getLocal().setDirectory(dir.toString());
        s3 = mock(S3Client.class);
        s3Factory = cfg -> {
            s3Built.incrementAndGet();
            return new S3DocumentStorageAdapter(s3, cfg);
        };
        logger = (Logger) LoggerFactory.getLogger(DocumentStorage.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    private void configureBucket() {
        properties.getS3().setBucket("jihajoor-documents");
        properties.getS3().setRegion("ap-south-1");
    }

    private void bucketUnreachable() {
        when(s3.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(SdkClientException.create("Unable to execute HTTP request: connect timed out"));
    }

    private List<ILoggingEvent> warnings() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    private static String expectedKey(String ext) {
        return "providers/" + PROVIDER + "/GOVERNMENT_ID/" + DOCUMENT + "." + ext;
    }

    // ----- auto ---------------------------------------------------------------

    @Test
    void autoWithoutABucketWritesLocallyAndNeverBuildsAnS3Client() {
        DocumentStorage storage = DocumentStorage.create(properties, s3Factory);

        assertThat(storage.writeKind()).isEqualTo(DocumentStorageKind.LOCAL);
        assertThat(s3Built).hasValue(0);
        assertThat(warnings()).isEmpty();
    }

    @Test
    void autoWithAReachableBucketWritesToS3() {
        configureBucket();

        DocumentStorage storage = DocumentStorage.create(properties, s3Factory);

        assertThat(storage.writeKind()).isEqualTo(DocumentStorageKind.S3);
        verify(s3).headBucket(any(HeadBucketRequest.class));
        StoredDocument stored = storage.store(PROVIDER, DocumentType.GOVERNMENT_ID, DOCUMENT, ID_SCAN);
        assertThat(stored).isEqualTo(new StoredDocument(DocumentStorageKind.S3, expectedKey("jpg")));
        verify(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void autoWithAnUnreachableBucketFallsBackToLocalWithAWarning() {
        configureBucket();
        bucketUnreachable();

        DocumentStorage storage = DocumentStorage.create(properties, s3Factory);

        assertThat(storage.writeKind()).isEqualTo(DocumentStorageKind.LOCAL);
        assertThat(warnings()).singleElement().satisfies(warning -> assertThat(warning.getFormattedMessage())
                .contains("jihajoor-documents", "not reachable", "connect timed out", "not visible to other instances"));
        StoredDocument stored = storage.store(PROVIDER, DocumentType.GOVERNMENT_ID, DOCUMENT, ID_SCAN);
        assertThat(stored.kind()).isEqualTo(DocumentStorageKind.LOCAL);
        assertThat(dir.resolve(stored.key())).exists();
        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void autoWithABucketButNoRegionWarnsAndWritesLocally() {
        properties.getS3().setBucket("jihajoor-documents");

        DocumentStorage storage = DocumentStorage.create(properties, s3Factory);

        assertThat(storage.writeKind()).isEqualTo(DocumentStorageKind.LOCAL);
        assertThat(s3Built).hasValue(0);
        assertThat(warnings()).singleElement().satisfies(warning ->
                assertThat(warning.getFormattedMessage()).contains("no region"));
    }

    @Test
    void anEndpointOverrideNeedsNoRegion() {
        properties.getS3().setBucket("jihajoor-documents");
        properties.getS3().setEndpoint("http://minio:9000");

        assertThat(properties.getS3().effectiveRegion()).isEqualTo("us-east-1");
        assertThat(DocumentStorage.create(properties, s3Factory).writeKind()).isEqualTo(DocumentStorageKind.S3);
    }

    // ----- forced modes -------------------------------------------------------

    @Test
    void forcedS3WithAReachableBucketWritesToS3() {
        configureBucket();
        properties.setProvider(Mode.S3);

        assertThat(DocumentStorage.create(properties, s3Factory).writeKind()).isEqualTo(DocumentStorageKind.S3);
    }

    @Test
    void forcedS3WithAnUnreachableBucketFailsStartupAndClosesTheClient() {
        configureBucket();
        bucketUnreachable();
        properties.setProvider(Mode.S3);

        assertThatThrownBy(() -> DocumentStorage.create(properties, s3Factory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not reachable");
        verify(s3).close();
    }

    @Test
    void forcedS3WithoutABucketFailsStartup() {
        properties.setProvider(Mode.S3);

        assertThatThrownBy(() -> DocumentStorage.create(properties, s3Factory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no bucket");
    }

    @Test
    void forcedS3WithoutARegionFailsStartup() {
        properties.getS3().setBucket("jihajoor-documents");
        properties.setProvider(Mode.S3);

        assertThatThrownBy(() -> DocumentStorage.create(properties, s3Factory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no region");
    }

    @Test
    void forcedLocalNeverChecksOrWritesS3ButKeepsItForReading() throws Exception {
        configureBucket();
        properties.setProvider(Mode.LOCAL);
        when(s3.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(
                GetObjectResponse.builder().contentLength(2L).build(),
                (InputStream) new ByteArrayInputStream(new byte[]{4, 5})));

        DocumentStorage storage = DocumentStorage.create(properties, s3Factory);

        assertThat(storage.writeKind()).isEqualTo(DocumentStorageKind.LOCAL);
        verify(s3, never()).headBucket(any(HeadBucketRequest.class));
        try (InputStream in = storage.load(DocumentStorageKind.S3, "providers/p/GOVERNMENT_ID/d.pdf")
                .orElseThrow().stream()) {
            assertThat(in.readAllBytes()).containsExactly(4, 5);
        }
    }

    // ----- reading back both kinds ---------------------------------------------

    @Test
    void afterS3IsTurnedOnDocumentsStoredLocallyBeforeStillReadBack() throws Exception {
        // Before: no bucket, the document goes to disk.
        StoredDocument before = DocumentStorage.create(properties, s3Factory)
                .store(PROVIDER, DocumentType.GOVERNMENT_ID, DOCUMENT, ID_SCAN);
        assertThat(before.kind()).isEqualTo(DocumentStorageKind.LOCAL);

        // After: S3 configured and reachable; the local row is still served from disk.
        configureBucket();
        DocumentStorage withS3 = DocumentStorage.create(properties, s3Factory);
        assertThat(withS3.writeKind()).isEqualTo(DocumentStorageKind.S3);

        try (InputStream in = withS3.load(before.kind(), before.key()).orElseThrow().stream()) {
            assertThat(in.readAllBytes()).containsExactly(1, 2, 3);
        }
        verify(s3, never()).getObject(any(GetObjectRequest.class));
    }

    @Test
    void anS3RowOnAnInstanceWithoutS3IsAStorageFailureNotANotFound() {
        DocumentStorage localOnly = DocumentStorage.create(properties, s3Factory);

        assertThatThrownBy(() -> localOnly.load(DocumentStorageKind.S3, "providers/p/GOVERNMENT_ID/d.pdf"))
                .isInstanceOf(DocumentStorageException.class);
    }

    @Test
    void aWriteFailureAfterS3WasChosenIsNotRedirectedToLocalDisk() throws Exception {
        configureBucket();
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(SdkClientException.create("connection reset"));
        DocumentStorage storage = DocumentStorage.create(properties, s3Factory);

        assertThatThrownBy(() -> storage.store(PROVIDER, DocumentType.GOVERNMENT_ID, DOCUMENT, ID_SCAN))
                .isInstanceOf(DocumentStorageException.class);
        try (var files = Files.walk(dir)) {
            assertThat(files.filter(Files::isRegularFile)).isEmpty();
        }
    }

    // ----- keys ---------------------------------------------------------------

    @Test
    void theKeyNamesTheProviderTypeAndDocument() {
        StoredDocument stored = DocumentStorage.create(properties, s3Factory)
                .store(PROVIDER, DocumentType.GOVERNMENT_ID, DOCUMENT,
                        new DocumentFile("scan.PDF", "application/pdf", new byte[]{1}));

        assertThat(stored.key()).isEqualTo(expectedKey("pdf"));
    }

    @Test
    void theExtensionFollowsTheContentTypeThenTheFileName() {
        assertThat(DocumentStorage.extension(new DocumentFile("a", "image/png", new byte[1]))).isEqualTo("png");
        assertThat(DocumentStorage.extension(new DocumentFile("a", "application/pdf; charset=binary", new byte[1])))
                .isEqualTo("pdf");
        assertThat(DocumentStorage.extension(new DocumentFile("cert.HEIC", "application/x-unknown", new byte[1])))
                .isEqualTo("heic");
        assertThat(DocumentStorage.extension(new DocumentFile("../../x", null, new byte[1]))).isEqualTo("bin");
    }

    @Test
    void closeClosesTheS3Client() {
        configureBucket();

        DocumentStorage.create(properties, s3Factory).close();

        verify(s3).close();
    }
}
