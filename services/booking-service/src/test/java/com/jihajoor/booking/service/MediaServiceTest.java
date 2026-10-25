package com.jihajoor.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.jihajoor.booking.config.BookingProperties;
import com.jihajoor.booking.domain.JobMedia;
import com.jihajoor.booking.domain.JobMediaRepository;
import com.jihajoor.booking.domain.MediaStorageKind;
import com.jihajoor.booking.media.MediaContent;
import com.jihajoor.booking.media.MediaFile;
import com.jihajoor.booking.media.MediaStorage;
import com.jihajoor.booking.media.MediaStorageException;
import com.jihajoor.booking.media.StoredMedia;

/**
 * Verifies media upload constraints (Requirement 7.2): up to 10 files, each &le; 50 MB, in
 * JPEG/PNG/MP4/MOV format; that each row records the store and key its bytes went to; that a
 * store failure is a retriable 503 recording nothing; and reading stored media back.
 */
@ExtendWith(MockitoExtension.class)
class MediaServiceTest {

    private static final UUID BOOKING = UUID.randomUUID();

    @Mock
    private MediaStorage storage;
    @Mock
    private JobMediaRepository mediaRepository;

    private MediaService service;

    @BeforeEach
    void setUp() {
        service = new MediaService(storage, mediaRepository, new BookingProperties());
        lenient().when(storage.store(any(), any(), any()))
                .thenAnswer(inv -> new StoredMedia(MediaStorageKind.S3,
                        "bookings/" + inv.getArgument(0) + "/" + inv.getArgument(1) + ".jpg"));
        lenient().when(mediaRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static MediaFile file(String contentType, long size) {
        return new MediaFile("f", contentType, size, contentOf(contentType));
    }

    /** The first bytes of a real file of that type, so the signature check passes. */
    private static byte[] contentOf(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
            case "image/png" -> new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
            case "video/mp4", "video/quicktime" -> new byte[]{0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
            default -> new byte[]{1};
        };
    }

    @Test
    void refusesAFileWhoseContentIsNotTheDeclaredType() {
        when(mediaRepository.countByBookingId(BOOKING)).thenReturn(0L);
        MediaFile html = new MediaFile("x.jpg", "image/jpeg", 20,
                "<script>alert(1)</script>".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.attach(BOOKING, "BEFORE_PHOTO", List.of(html)))
                .isInstanceOf(BookingException.class);
        verify(storage, org.mockito.Mockito.never()).store(any(), any(), any());
    }

    @Test
    void storesValidFiles() {
        when(mediaRepository.countByBookingId(BOOKING)).thenReturn(0L);
        List<JobMedia> stored = service.attach(BOOKING, "CUSTOMER_UPLOAD", List.of(
                file("image/jpeg", 1024),
                file("video/mp4", 1024)));
        assertThat(stored).hasSize(2);
        verify(storage, org.mockito.Mockito.times(2)).store(any(), any(), any());
    }

    @Test
    void eachRowRecordsTheStoreAndAKeyNamedAfterItsOwnId() {
        when(mediaRepository.countByBookingId(BOOKING)).thenReturn(0L);

        JobMedia media = service.attach(BOOKING, "BEFORE_PHOTO", List.of(file("image/jpeg", 10))).get(0);

        assertThat(media.getStorage()).isEqualTo(MediaStorageKind.S3);
        assertThat(media.getS3Key()).isEqualTo("bookings/" + BOOKING + "/" + media.getId() + ".jpg");
        assertThat(media.getBookingId()).isEqualTo(BOOKING);
        assertThat(media.getType()).isEqualTo("BEFORE_PHOTO");
        assertThat(media.getContentType()).isEqualTo("image/jpeg");
    }

    @Test
    void storeFailureIsARetriable503AndRecordsNothing() {
        when(mediaRepository.countByBookingId(BOOKING)).thenReturn(0L);
        when(storage.store(any(), any(), any())).thenThrow(new MediaStorageException("S3 down"));

        assertThatThrownBy(() -> service.attach(BOOKING, "BEFORE_PHOTO", List.of(file("image/jpeg", 10))))
                .isInstanceOfSatisfying(BookingException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getErrorCode()).isEqualTo("MEDIA_STORAGE_UNAVAILABLE");
                });
        verify(mediaRepository, never()).saveAll(anyList());
    }

    @Test
    void acceptsAllFourAllowedFormats() {
        when(mediaRepository.countByBookingId(BOOKING)).thenReturn(0L);
        List<JobMedia> stored = service.attach(BOOKING, "CUSTOMER_UPLOAD", List.of(
                file("image/jpeg", 10),
                file("image/png", 10),
                file("video/mp4", 10),
                file("video/quicktime", 10)));
        assertThat(stored).hasSize(4);
    }

    @Test
    void rejectsUnsupportedFormat() {
        when(mediaRepository.countByBookingId(BOOKING)).thenReturn(0L);
        assertThatThrownBy(() -> service.attach(BOOKING, "CUSTOMER_UPLOAD",
                List.of(file("application/pdf", 10))))
                .isInstanceOf(BookingException.class)
                .hasMessageContaining("Unsupported media type");
        verify(storage, never()).store(any(), any(), any());
    }

    @Test
    void rejectsFileOverFiftyMegabytes() {
        when(mediaRepository.countByBookingId(BOOKING)).thenReturn(0L);
        long overLimit = 52_428_800L + 1;
        assertThatThrownBy(() -> service.attach(BOOKING, "CUSTOMER_UPLOAD",
                List.of(file("image/png", overLimit))))
                .isInstanceOf(BookingException.class)
                .hasMessageContaining("maximum size");
        verify(storage, never()).store(any(), any(), any());
    }

    @Test
    void acceptsFileExactlyAtFiftyMegabytes() {
        when(mediaRepository.countByBookingId(BOOKING)).thenReturn(0L);
        List<JobMedia> stored = service.attach(BOOKING, "CUSTOMER_UPLOAD",
                List.of(file("image/png", 52_428_800L)));
        assertThat(stored).hasSize(1);
    }

    @Test
    void rejectsBatchExceedingTenFilesPerBooking() {
        when(mediaRepository.countByBookingId(BOOKING)).thenReturn(0L);
        List<MediaFile> eleven = java.util.stream.IntStream.range(0, 11)
                .mapToObj(i -> file("image/jpeg", 10))
                .toList();
        assertThatThrownBy(() -> service.attach(BOOKING, "CUSTOMER_UPLOAD", eleven))
                .isInstanceOf(BookingException.class)
                .hasMessageContaining("at most 10");
        verify(storage, never()).store(any(), any(), any());
    }

    @Test
    void rejectsWhenExistingPlusNewExceedsLimit() {
        when(mediaRepository.countByBookingId(BOOKING)).thenReturn(8L);
        List<MediaFile> three = List.of(
                file("image/jpeg", 10), file("image/jpeg", 10), file("image/jpeg", 10));
        assertThatThrownBy(() -> service.attach(BOOKING, "CUSTOMER_UPLOAD", three))
                .isInstanceOf(BookingException.class);
    }

    @Test
    void emptyBatchIsANoop() {
        assertThat(service.attach(BOOKING, "CUSTOMER_UPLOAD", List.of())).isEmpty();
        verify(storage, never()).store(any(), any(), any());
    }

    // ----- reading back ------------------------------------------------------

    @Test
    void openReadsTheBytesFromTheStoreTheRowNames() {
        JobMedia media = JobMedia.stored(UUID.randomUUID(), BOOKING, "AFTER_PHOTO", "image/png", 3,
                MediaStorageKind.S3, "prod/bookings/x.png");
        MediaContent content = new MediaContent(new ByteArrayInputStream(new byte[]{1, 2, 3}), 3);
        when(mediaRepository.findById(media.getId())).thenReturn(Optional.of(media));
        when(storage.load(MediaStorageKind.S3, "prod/bookings/x.png")).thenReturn(Optional.of(content));

        MediaService.MediaDownload download = service.open(BOOKING, media.getId());

        assertThat(download.media()).isSameAs(media);
        assertThat(download.content()).isSameAs(content);
    }

    @Test
    void openRefusesMediaOfAnotherBooking() {
        JobMedia media = JobMedia.of(UUID.randomUUID(), "AFTER_PHOTO", "image/png", 3, "k");
        when(mediaRepository.findById(media.getId())).thenReturn(Optional.of(media));

        assertThatThrownBy(() -> service.open(BOOKING, media.getId()))
                .isInstanceOfSatisfying(BookingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("MEDIA_NOT_FOUND"));
        verify(storage, never()).load(any(), any());
    }

    @Test
    void openAnswers404WhenTheBytesWereNeverStored() {
        // A row from before media was kept: LOCAL, with nothing on disk under its key.
        JobMedia legacy = JobMedia.of(BOOKING, "BEFORE_PHOTO", "image/jpeg", 3, "bookings/b/media/old");
        when(mediaRepository.findById(legacy.getId())).thenReturn(Optional.of(legacy));
        when(storage.load(MediaStorageKind.LOCAL, "bookings/b/media/old")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.open(BOOKING, legacy.getId()))
                .isInstanceOfSatisfying(BookingException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getErrorCode()).isEqualTo("MEDIA_NOT_FOUND");
                });
    }

    @Test
    void openAnswers503WhenTheStoreCannotBeRead() {
        JobMedia media = JobMedia.stored(UUID.randomUUID(), BOOKING, "AFTER_PHOTO", "image/png", 3,
                MediaStorageKind.S3, "k");
        when(mediaRepository.findById(media.getId())).thenReturn(Optional.of(media));
        when(storage.load(MediaStorageKind.S3, "k")).thenThrow(new MediaStorageException("timeout"));

        assertThatThrownBy(() -> service.open(BOOKING, media.getId()))
                .isInstanceOfSatisfying(BookingException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getErrorCode()).isEqualTo("MEDIA_STORAGE_UNAVAILABLE");
                });
    }
}
