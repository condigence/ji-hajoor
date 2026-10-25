package com.jihajoor.booking.media;

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

import com.jihajoor.booking.config.MediaStorageProperties;
import com.jihajoor.booking.config.MediaStorageProperties.Mode;
import com.jihajoor.booking.domain.MediaStorageKind;

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
 * "S3 if it is available, otherwise local": which store {@link MediaStorage#create} writes to for
 * each mode and configuration, that an unreachable bucket in auto mode degrades to local with a
 * WARN rather than failing startup, that the forced modes are honoured, and that rows of either
 * kind are read back from where they were written, whatever this process writes to.
 */
class MediaStorageTest {

    private static final UUID BOOKING = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MEDIA = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final MediaFile PHOTO = new MediaFile("p.jpg", "image/jpeg", 3, new byte[]{1, 2, 3});

    @TempDir
    Path dir;

    private MediaStorageProperties properties;
    private S3Client s3;
    private final AtomicInteger s3Built = new AtomicInteger();
    private Function<MediaStorageProperties.S3, S3MediaStorageAdapter> s3Factory;
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void setUp() {
        properties = new MediaStorageProperties();
        properties.getLocal().setDirectory(dir.toString());
        s3 = mock(S3Client.class);
        s3Factory = cfg -> {
            s3Built.incrementAndGet();
            return new S3MediaStorageAdapter(s3, cfg);
        };
        logger = (Logger) LoggerFactory.getLogger(MediaStorage.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    private void configureBucket() {
        properties.getS3().setBucket("jihajoor-media");
        properties.getS3().setRegion("ap-south-1");
    }

    private void bucketUnreachable() {
        when(s3.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(SdkClientException.create("Unable to execute HTTP request: connect timed out"));
    }

    private List<ILoggingEvent> warnings() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    // ----- auto ---------------------------------------------------------------

    @Test
    void autoWithoutABucketWritesLocallyAndNeverBuildsAnS3Client() {
        MediaStorage storage = MediaStorage.create(properties, s3Factory);

        assertThat(storage.writeKind()).isEqualTo(MediaStorageKind.LOCAL);
        assertThat(s3Built).hasValue(0);
        assertThat(warnings()).isEmpty();
    }

    @Test
    void autoWithAReachableBucketWritesToS3() {
        configureBucket();

        MediaStorage storage = MediaStorage.create(properties, s3Factory);

        assertThat(storage.writeKind()).isEqualTo(MediaStorageKind.S3);
        verify(s3).headBucket(any(HeadBucketRequest.class));
        StoredMedia stored = storage.store(BOOKING, MEDIA, PHOTO);
        assertThat(stored).isEqualTo(new StoredMedia(MediaStorageKind.S3, "bookings/" + BOOKING + "/" + MEDIA + ".jpg"));
        verify(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void autoWithAnUnreachableBucketFallsBackToLocalWithAWarning() {
        configureBucket();
        bucketUnreachable();

        MediaStorage storage = MediaStorage.create(properties, s3Factory);

        assertThat(storage.writeKind()).isEqualTo(MediaStorageKind.LOCAL);
        assertThat(warnings()).singleElement().satisfies(warning -> assertThat(warning.getFormattedMessage())
                .contains("jihajoor-media", "not reachable", "connect timed out", "not visible to other instances"));
        StoredMedia stored = storage.store(BOOKING, MEDIA, PHOTO);
        assertThat(stored.kind()).isEqualTo(MediaStorageKind.LOCAL);
        assertThat(dir.resolve(stored.key())).exists();
        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void autoWithABucketButNoRegionWarnsAndWritesLocally() {
        properties.getS3().setBucket("jihajoor-media");

        MediaStorage storage = MediaStorage.create(properties, s3Factory);

        assertThat(storage.writeKind()).isEqualTo(MediaStorageKind.LOCAL);
        assertThat(s3Built).hasValue(0);
        assertThat(warnings()).singleElement().satisfies(warning ->
                assertThat(warning.getFormattedMessage()).contains("no region"));
    }

    @Test
    void anEndpointOverrideNeedsNoRegion() {
        properties.getS3().setBucket("jihajoor-media");
        properties.getS3().setEndpoint("http://minio:9000");

        assertThat(properties.getS3().effectiveRegion()).isEqualTo("us-east-1");
        assertThat(MediaStorage.create(properties, s3Factory).writeKind()).isEqualTo(MediaStorageKind.S3);
    }

    // ----- forced modes -------------------------------------------------------

    @Test
    void forcedS3WithAReachableBucketWritesToS3() {
        configureBucket();
        properties.setProvider(Mode.S3);

        assertThat(MediaStorage.create(properties, s3Factory).writeKind()).isEqualTo(MediaStorageKind.S3);
    }

    @Test
    void forcedS3WithAnUnreachableBucketFailsStartupAndClosesTheClient() {
        configureBucket();
        bucketUnreachable();
        properties.setProvider(Mode.S3);

        assertThatThrownBy(() -> MediaStorage.create(properties, s3Factory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not reachable");
        verify(s3).close();
    }

    @Test
    void forcedS3WithoutABucketFailsStartup() {
        properties.setProvider(Mode.S3);

        assertThatThrownBy(() -> MediaStorage.create(properties, s3Factory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no bucket");
    }

    @Test
    void forcedS3WithoutARegionFailsStartup() {
        properties.getS3().setBucket("jihajoor-media");
        properties.setProvider(Mode.S3);

        assertThatThrownBy(() -> MediaStorage.create(properties, s3Factory))
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

        MediaStorage storage = MediaStorage.create(properties, s3Factory);

        assertThat(storage.writeKind()).isEqualTo(MediaStorageKind.LOCAL);
        verify(s3, never()).headBucket(any(HeadBucketRequest.class));
        try (InputStream in = storage.load(MediaStorageKind.S3, "bookings/b/m.jpg").orElseThrow().stream()) {
            assertThat(in.readAllBytes()).containsExactly(4, 5);
        }
    }

    // ----- reading back both kinds ---------------------------------------------

    @Test
    void afterS3IsTurnedOnPhotosStoredLocallyBeforeStillReadBack() throws Exception {
        // Before: no bucket, the photo goes to disk.
        StoredMedia before = MediaStorage.create(properties, s3Factory).store(BOOKING, MEDIA, PHOTO);
        assertThat(before.kind()).isEqualTo(MediaStorageKind.LOCAL);

        // After: S3 configured and reachable; the local row is still served from disk.
        configureBucket();
        MediaStorage withS3 = MediaStorage.create(properties, s3Factory);
        assertThat(withS3.writeKind()).isEqualTo(MediaStorageKind.S3);

        try (InputStream in = withS3.load(before.kind(), before.key()).orElseThrow().stream()) {
            assertThat(in.readAllBytes()).containsExactly(1, 2, 3);
        }
        verify(s3, never()).getObject(any(GetObjectRequest.class));
    }

    @Test
    void anS3RowOnAnInstanceWithoutS3IsAStorageFailureNotANotFound() {
        MediaStorage localOnly = MediaStorage.create(properties, s3Factory);

        assertThatThrownBy(() -> localOnly.load(MediaStorageKind.S3, "bookings/b/m.jpg"))
                .isInstanceOf(MediaStorageException.class);
    }

    @Test
    void aWriteFailureAfterS3WasChosenIsNotRedirectedToLocalDisk() throws Exception {
        configureBucket();
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(SdkClientException.create("connection reset"));
        MediaStorage storage = MediaStorage.create(properties, s3Factory);

        assertThatThrownBy(() -> storage.store(BOOKING, MEDIA, PHOTO))
                .isInstanceOf(MediaStorageException.class);
        try (var files = Files.walk(dir)) {
            assertThat(files.filter(Files::isRegularFile)).isEmpty();
        }
    }

    // ----- keys ---------------------------------------------------------------

    @Test
    void theExtensionFollowsTheContentTypeThenTheFileName() {
        assertThat(MediaStorage.extension(new MediaFile("a", "image/png", 1, new byte[1]))).isEqualTo("png");
        assertThat(MediaStorage.extension(new MediaFile("a", "video/quicktime", 1, new byte[1]))).isEqualTo("mov");
        assertThat(MediaStorage.extension(new MediaFile("clip.MP4", "video/x-unknown", 1, new byte[1]))).isEqualTo("mp4");
        assertThat(MediaStorage.extension(new MediaFile("../../x", null, 1, new byte[1]))).isEqualTo("bin");
    }

    @Test
    void closeClosesTheS3Client() {
        configureBucket();

        MediaStorage.create(properties, s3Factory).close();

        verify(s3).close();
    }
}
