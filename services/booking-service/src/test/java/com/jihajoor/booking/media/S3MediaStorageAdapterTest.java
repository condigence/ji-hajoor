package com.jihajoor.booking.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.jihajoor.booking.config.MediaStorageProperties;
import com.jihajoor.booking.domain.MediaStorageKind;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/**
 * The S3 store over a mocked {@link S3Client}: objects are written under the configured prefix with
 * the upload's content type and the requested server-side encryption, read back with their length,
 * a missing key reads as empty, and every other SDK failure is a {@link MediaStorageException}
 * (never a silent fallback).
 */
class S3MediaStorageAdapterTest {

    private static final MediaFile PHOTO = new MediaFile("p.jpg", "image/jpeg", 3, new byte[]{7, 8, 9});

    private S3Client s3;
    private MediaStorageProperties.S3 config;

    @BeforeEach
    void setUp() {
        s3 = mock(S3Client.class);
        config = new MediaStorageProperties.S3();
        config.setBucket("jihajoor-media");
        config.setRegion("ap-south-1");
    }

    @Test
    void putWritesTheObjectUnderThePrefixWithItsContentType() throws Exception {
        config.setPrefix("/prod");
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        S3MediaStorageAdapter adapter = new S3MediaStorageAdapter(s3, config);

        String stored = adapter.put("bookings/b1/m1.jpg", PHOTO);

        assertThat(adapter.kind()).isEqualTo(MediaStorageKind.S3);
        assertThat(stored).isEqualTo("prod/bookings/b1/m1.jpg");
        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3).putObject(request.capture(), body.capture());
        assertThat(request.getValue().bucket()).isEqualTo("jihajoor-media");
        assertThat(request.getValue().key()).isEqualTo("prod/bookings/b1/m1.jpg");
        assertThat(request.getValue().contentType()).isEqualTo("image/jpeg");
        assertThat(request.getValue().contentLength()).isEqualTo(3L);
        // Blank setting: the bucket's default encryption applies, nothing is requested per object.
        assertThat(request.getValue().serverSideEncryption()).isNull();
        try (InputStream in = body.getValue().contentStreamProvider().newStream()) {
            assertThat(in.readAllBytes()).containsExactly(7, 8, 9);
        }
    }

    @Test
    void putRequestsSseKmsWithTheConfiguredKey() {
        config.setServerSideEncryption("aws:kms");
        config.setKmsKeyId("arn:aws:kms:ap-south-1:111122223333:key/abc");
        S3MediaStorageAdapter adapter = new S3MediaStorageAdapter(s3, config);

        adapter.put("bookings/b1/m1.jpg", PHOTO);

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().serverSideEncryption()).isEqualTo(ServerSideEncryption.AWS_KMS);
        assertThat(request.getValue().ssekmsKeyId()).isEqualTo("arn:aws:kms:ap-south-1:111122223333:key/abc");
    }

    @Test
    void anUnknownEncryptionSettingIsRefusedAtConstruction() {
        config.setServerSideEncryption("rot13");

        assertThatThrownBy(() -> new S3MediaStorageAdapter(s3, config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("server-side-encryption");
    }

    @Test
    void aKmsKeyWithoutKmsEncryptionIsRefused() {
        config.setServerSideEncryption("AES256");
        config.setKmsKeyId("key");

        assertThatThrownBy(() -> new S3MediaStorageAdapter(s3, config))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void putFailureIsAMediaStorageException() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(SdkClientException.create("connect timed out"));
        S3MediaStorageAdapter adapter = new S3MediaStorageAdapter(s3, config);

        assertThatThrownBy(() -> adapter.put("bookings/b1/m1.jpg", PHOTO))
                .isInstanceOf(MediaStorageException.class)
                .hasMessageContaining("s3://jihajoor-media/bookings/b1/m1.jpg");
    }

    @Test
    void getReturnsTheObjectStreamAndLength() throws Exception {
        GetObjectResponse response = GetObjectResponse.builder().contentLength(3L).contentType("image/jpeg").build();
        when(s3.getObject(any(GetObjectRequest.class))).thenReturn(
                new ResponseInputStream<>(response, (InputStream) new ByteArrayInputStream(new byte[]{7, 8, 9})));
        S3MediaStorageAdapter adapter = new S3MediaStorageAdapter(s3, config);

        MediaContent content = adapter.get("prod/bookings/b1/m1.jpg").orElseThrow();

        assertThat(content.length()).isEqualTo(3);
        try (InputStream in = content.stream()) {
            assertThat(in.readAllBytes()).containsExactly(7, 8, 9);
        }
        ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3).getObject(request.capture());
        // The stored key already carries the prefix; it is used as is.
        assertThat(request.getValue().key()).isEqualTo("prod/bookings/b1/m1.jpg");
        assertThat(request.getValue().bucket()).isEqualTo("jihajoor-media");
    }

    @Test
    void aMissingKeyReadsAsEmpty() {
        when(s3.getObject(any(GetObjectRequest.class))).thenThrow(NoSuchKeyException.builder().message("gone").build());
        S3MediaStorageAdapter adapter = new S3MediaStorageAdapter(s3, config);

        assertThat(adapter.get("bookings/b1/m1.jpg")).isEmpty();
    }

    @Test
    void anyOtherReadFailureIsAMediaStorageException() {
        when(s3.getObject(any(GetObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(403).message("AccessDenied").build());
        S3MediaStorageAdapter adapter = new S3MediaStorageAdapter(s3, config);

        assertThatThrownBy(() -> adapter.get("bookings/b1/m1.jpg"))
                .isInstanceOf(MediaStorageException.class);
    }

    @Test
    void verifyReachableAsksHeadBucketWithinTheTimeout() {
        S3MediaStorageAdapter adapter = new S3MediaStorageAdapter(s3, config);

        adapter.verifyReachable(Duration.ofSeconds(3));

        ArgumentCaptor<HeadBucketRequest> request = ArgumentCaptor.forClass(HeadBucketRequest.class);
        verify(s3).headBucket(request.capture());
        assertThat(request.getValue().bucket()).isEqualTo("jihajoor-media");
        assertThat(request.getValue().overrideConfiguration().orElseThrow().apiCallTimeout())
                .contains(Duration.ofSeconds(3));
    }

    @Test
    void closeClosesTheClient() {
        new S3MediaStorageAdapter(s3, config).close();

        verify(s3).close();
    }
}
