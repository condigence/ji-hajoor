package com.jihajoor.booking.api;

import java.time.Duration;
import java.util.UUID;

import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jihajoor.booking.domain.Booking;
import com.jihajoor.booking.service.BookingQueryService;
import com.jihajoor.booking.service.MediaService;
import com.jihajoor.booking.service.MediaService.MediaDownload;

/**
 * Serves the bytes of a booking's stored photos and media: {@code GET /bookings/{bookingKey}/photos/{photoId}},
 * where {@code photoId} is the {@code id} of a {@code photos[]} entry of the booking detail.
 *
 * <h2>Why the bytes stream through this service</h2>
 * Photos may live in S3 or on local disk ({@code MediaStorage}), and one endpoint serves both the
 * same way. More importantly it keeps the detail's ownership rule: the booking must be visible to
 * the caller ({@link BookingQueryService#visibleBooking}: its customer, its assigned provider, or
 * staff), with the same 404 as for a missing booking otherwise, and the photo must belong to that
 * booking. A presigned S3 URL would be a bearer credential that outlives the check (anyone holding
 * it may fetch the photo until it expires) and has no local-disk equivalent.
 *
 * <p>The response is private and cacheable for an hour: a photo never changes under its id.
 * Role gating is {@code BookingRbacConfig}'s ({@code GET /bookings/*}{@code /photos/*}).
 */
@RestController
@RequestMapping("/bookings/{bookingKey}/photos")
public class BookingPhotoController {

    private static final Duration CACHE_FOR = Duration.ofHours(1);

    private final BookingQueryService queryService;
    private final MediaService mediaService;
    private final CallerIdentity callerIdentity;

    public BookingPhotoController(BookingQueryService queryService, MediaService mediaService,
                                  CallerIdentity callerIdentity) {
        this.queryService = queryService;
        this.mediaService = mediaService;
        this.callerIdentity = callerIdentity;
    }

    /**
     * The photo's bytes with its stored {@code Content-Type}. 404 {@code BOOKING_NOT_FOUND} when the
     * caller may not see the booking; 404 {@code MEDIA_NOT_FOUND} when the photo is not the
     * booking's or its bytes are not stored (e.g. photos recorded before media was kept); 503
     * {@code MEDIA_STORAGE_UNAVAILABLE} when the store cannot be read right now.
     */
    @GetMapping("/{photoId}")
    public ResponseEntity<Resource> photo(@PathVariable("bookingKey") String bookingKey,
                                          @PathVariable("photoId") UUID photoId) {
        Booking booking = queryService.visibleBooking(
                bookingKey, callerIdentity.requireCallerId(), callerIdentity.isStaff());
        MediaDownload download = mediaService.open(booking.getId(), photoId);

        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.media().getContentType()))
                .cacheControl(CacheControl.maxAge(CACHE_FOR).cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().build().toString());
        if (download.content().length() >= 0) {
            response.contentLength(download.content().length());
        }
        // Streamed, not buffered: the converter copies the stream to the response and closes it.
        return response.body(new InputStreamResource(download.content().stream()));
    }
}
