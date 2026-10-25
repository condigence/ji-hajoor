package com.jihajoor.booking.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.jihajoor.booking.catalog.CatalogClientPort;
import com.jihajoor.booking.config.BookingProperties;
import com.jihajoor.booking.config.MediaStorageProperties;
import com.jihajoor.booking.domain.Booking;
import com.jihajoor.booking.domain.JobMedia;
import com.jihajoor.booking.domain.JobMediaRepository;
import com.jihajoor.booking.domain.MediaStorageKind;
import com.jihajoor.booking.domain.PartsLineItemRepository;
import com.jihajoor.booking.media.LocalMediaStorageAdapter;
import com.jihajoor.booking.media.MediaFile;
import com.jihajoor.booking.media.MediaStorage;
import com.jihajoor.booking.media.S3MediaStorageAdapter;
import com.jihajoor.booking.service.BookingQueryService;
import com.jihajoor.booking.service.MediaService;
import com.jihajoor.booking.support.Bookings;
import com.jihajoor.booking.support.InMemoryBookingRepository;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/**
 * Web-layer tests for {@link BookingPhotoController}: a photo's bytes come back with their content
 * type whether they live in S3 or on local disk, under the booking detail's ownership rule
 * (customer, assigned provider, staff; the same 404 for anyone else), and only for a photo of
 * that booking.
 *
 * <p>Standalone MockMvc over the real query and media services, a real {@link MediaStorage} on a
 * temporary directory plus an S3 adapter over a mocked client, and the caller placed in the
 * {@code SecurityContextHolder} as the shared JWT filter does.
 */
class BookingPhotoControllerTest {

    private static final UUID CUSTOMER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PROVIDER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID STRANGER = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @TempDir
    Path dir;

    private InMemoryBookingRepository repository;
    private JobMediaRepository mediaRepository;
    private LocalMediaStorageAdapter local;
    private S3Client s3;
    private MockMvc mvc;
    private Booking booking;

    @BeforeEach
    void setUp() {
        repository = new InMemoryBookingRepository();
        mediaRepository = mock(JobMediaRepository.class);
        local = new LocalMediaStorageAdapter(dir);
        s3 = mock(S3Client.class);
        MediaStorageProperties.S3 s3Config = new MediaStorageProperties.S3();
        s3Config.setBucket("jihajoor-media");
        s3Config.setRegion("ap-south-1");
        // This instance writes to S3 and still reads the photos stored on disk before.
        MediaStorage storage = new MediaStorage(new S3MediaStorageAdapter(s3, s3Config), local);

        BookingQueryService queryService = new BookingQueryService(repository, mock(CatalogClientPort.class),
                mediaRepository, mock(PartsLineItemRepository.class), id -> Optional.empty());
        MediaService mediaService = new MediaService(storage, mediaRepository, new BookingProperties());
        mvc = MockMvcBuilders.standaloneSetup(
                        new BookingPhotoController(queryService, mediaService, new CallerIdentity()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new ResourceHttpMessageConverter(), new JacksonJsonHttpMessageConverter())
                .build();

        booking = repository.save(Bookings.placed(CUSTOMER, UUID.randomUUID(), "HFX-20260901-PHO001",
                Instant.parse("2026-09-01T08:00:00Z"), null));
        booking.setProviderId(PROVIDER);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(UUID subject, String... roles) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                subject.toString(), null,
                Arrays.stream(roles).map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList()));
    }

    private ResultActions getPhoto(Object bookingKey, UUID photoId) throws Exception {
        return mvc.perform(get("/bookings/{bookingKey}/photos/{photoId}", bookingKey.toString(), photoId));
    }

    private JobMedia localPhoto(byte[] bytes) {
        UUID id = UUID.randomUUID();
        String key = local.put("bookings/" + booking.getId() + "/" + id + ".png",
                new MediaFile("p.png", "image/png", bytes.length, bytes));
        JobMedia media = JobMedia.stored(id, booking.getId(), "BEFORE_PHOTO", "image/png", bytes.length,
                MediaStorageKind.LOCAL, key);
        when(mediaRepository.findById(id)).thenReturn(Optional.of(media));
        return media;
    }

    private JobMedia s3Photo(byte[] bytes) {
        UUID id = UUID.randomUUID();
        JobMedia media = JobMedia.stored(id, booking.getId(), "AFTER_PHOTO", "image/jpeg", bytes.length,
                MediaStorageKind.S3, "bookings/" + booking.getId() + "/" + id + ".jpg");
        when(mediaRepository.findById(id)).thenReturn(Optional.of(media));
        when(s3.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(
                GetObjectResponse.builder().contentLength((long) bytes.length).contentType("image/jpeg").build(),
                (InputStream) new ByteArrayInputStream(bytes)));
        return media;
    }

    @Test
    void theAssignedProviderReadsAnS3Photo() throws Exception {
        JobMedia photo = s3Photo(new byte[]{9, 8, 7});
        authenticateAs(PROVIDER, "SERVICE_PROVIDER");

        getPhoto(booking.getReference(), photo.getId())
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().longValue("Content-Length", 3))
                .andExpect(header().string("Cache-Control", "max-age=3600, private"))
                .andExpect(content().bytes(new byte[]{9, 8, 7}));
    }

    @Test
    void theCustomerReadsAPhotoStoredLocallyBeforeS3WasTurnedOn() throws Exception {
        JobMedia photo = localPhoto(new byte[]{1, 2, 3, 4});
        authenticateAs(CUSTOMER, "CUSTOMER");

        getPhoto(booking.getId(), photo.getId())
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(content().bytes(new byte[]{1, 2, 3, 4}));
    }

    @Test
    void staffReadAnyBookingsPhoto() throws Exception {
        JobMedia photo = localPhoto(new byte[]{1});
        authenticateAs(STRANGER, "SUPPORT_AGENT");

        getPhoto(booking.getId(), photo.getId()).andExpect(status().isOk());
    }

    @Test
    void anyoneElseGetsTheSame404AsForAMissingBooking() throws Exception {
        JobMedia photo = localPhoto(new byte[]{1});
        authenticateAs(STRANGER, "SERVICE_PROVIDER");

        getPhoto(booking.getId(), photo.getId())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("BOOKING_NOT_FOUND"));
    }

    @Test
    void aPhotoOfAnotherBookingIsNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(mediaRepository.findById(id)).thenReturn(Optional.of(JobMedia.stored(id, UUID.randomUUID(),
                "BEFORE_PHOTO", "image/png", 1, MediaStorageKind.LOCAL, "bookings/other/" + id + ".png")));
        authenticateAs(CUSTOMER, "CUSTOMER");

        getPhoto(booking.getId(), id)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("MEDIA_NOT_FOUND"));
    }

    @Test
    void aPhotoRecordedBeforeBytesWereKeptIsNotFound() throws Exception {
        JobMedia legacy = JobMedia.of(booking.getId(), "BEFORE_PHOTO", "image/jpeg", 10,
                "bookings/" + booking.getId() + "/media/" + UUID.randomUUID());
        when(mediaRepository.findById(legacy.getId())).thenReturn(Optional.of(legacy));
        authenticateAs(PROVIDER, "SERVICE_PROVIDER");

        getPhoto(booking.getId(), legacy.getId())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("MEDIA_NOT_FOUND"));
    }

    @Test
    void anUnreadableStoreIsARetriable503() throws Exception {
        JobMedia photo = s3Photo(new byte[]{1});
        when(s3.getObject(any(GetObjectRequest.class))).thenThrow(SdkClientException.create("read timed out"));
        authenticateAs(PROVIDER, "SERVICE_PROVIDER");

        getPhoto(booking.getId(), photo.getId())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("MEDIA_STORAGE_UNAVAILABLE"));
    }

    @Test
    void aMalformedPhotoIdIsA400() throws Exception {
        authenticateAs(CUSTOMER, "CUSTOMER");

        mvc.perform(get("/bookings/{bookingKey}/photos/not-a-uuid", booking.getId().toString()))
                .andExpect(status().isBadRequest());
    }
}
