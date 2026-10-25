package com.jihajoor.booking.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.jihajoor.booking.config.BookingProperties;
import com.jihajoor.booking.domain.JobMedia;
import com.jihajoor.booking.domain.JobMediaRepository;
import com.jihajoor.booking.media.MediaContent;
import com.jihajoor.booking.media.MediaFile;
import com.jihajoor.booking.media.MediaStorage;
import com.jihajoor.booking.media.MediaStorageException;
import com.jihajoor.booking.media.StoredMedia;

/**
 * Validates, stores and reads back booking media (Requirement 7.2): up to 10 files per booking,
 * each no larger than 50 MB and in JPEG, PNG, MP4, or MOV format. The bytes go to the
 * {@link MediaStorage} (S3 when it is configured and reachable, local disk otherwise); each
 * {@code job_media} row records which store holds them.
 *
 * <h2>No transaction around the upload</h2>
 * {@link #attach} deliberately has no {@code @Transactional}: a transaction opened at the count
 * query would keep a pooled database connection checked out while up to 10 files of 50 MB each are
 * sent to S3. Instead every file is stored first and the rows are then saved together
 * ({@code saveAll}, one transaction), so a failed upload records nothing. A caller that already
 * holds a transaction (booking creation) still gets the rows written inside it. The price is an
 * orphaned object when the row insert fails after the upload, which is harmless: nothing points at
 * it.
 */
@Service
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    private final MediaStorage storage;
    private final JobMediaRepository mediaRepository;
    private final BookingProperties properties;

    public MediaService(MediaStorage storage,
                        JobMediaRepository mediaRepository,
                        BookingProperties properties) {
        this.storage = storage;
        this.mediaRepository = mediaRepository;
        this.properties = properties;
    }

    /**
     * Validates the batch as a whole (count limit is per booking, counting already-stored
     * media) and each file (size + content type), then stores each and persists a
     * {@code job_media} row per file.
     *
     * @throws BookingException 422 if any constraint is violated (Requirement 7.2); 503
     *         {@code MEDIA_STORAGE_UNAVAILABLE} if the store refused a file, in which case no row
     *         is recorded and the client should retry
     */
    public List<JobMedia> attach(UUID bookingId, String type, List<MediaFile> files) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        validateBatch(bookingId, files);

        List<JobMedia> media = new ArrayList<>(files.size());
        for (MediaFile file : files) {
            UUID mediaId = UUID.randomUUID();
            StoredMedia stored = store(bookingId, mediaId, file);
            media.add(JobMedia.stored(mediaId, bookingId, type, file.contentType(), file.sizeBytes(),
                    stored.kind(), stored.key()));
        }
        return mediaRepository.saveAll(media);
    }

    /**
     * Media {@code mediaId} of booking {@code bookingId} with its bytes, for streaming back. The
     * caller has already established that the requester may see the booking.
     *
     * @throws BookingException 404 {@code MEDIA_NOT_FOUND} when the media does not belong to the
     *         booking or its bytes are not stored; 503 {@code MEDIA_STORAGE_UNAVAILABLE} when the
     *         store cannot be read right now
     */
    public MediaDownload open(UUID bookingId, UUID mediaId) {
        JobMedia media = mediaRepository.findById(mediaId)
                .filter(m -> m.getBookingId().equals(bookingId))
                .orElseThrow(() -> BookingException.mediaNotFound(mediaId));
        try {
            MediaContent content = storage.load(media.getStorage(), media.getS3Key())
                    .orElseThrow(() -> BookingException.mediaNotFound(mediaId));
            return new MediaDownload(media, content);
        } catch (MediaStorageException e) {
            log.warn("Media {} of booking {} could not be read from {}: {}",
                    mediaId, bookingId, media.getStorage(), e.getMessage());
            throw BookingException.mediaStorageUnavailable();
        }
    }

    /** A stored media row and its bytes; the caller closes {@code content().stream()}. */
    public record MediaDownload(JobMedia media, MediaContent content) {
    }

    private StoredMedia store(UUID bookingId, UUID mediaId, MediaFile file) {
        try {
            return storage.store(bookingId, mediaId, file);
        } catch (MediaStorageException e) {
            log.warn("Media upload for booking {} failed in {} storage: {}",
                    bookingId, storage.writeKind(), e.getMessage());
            throw BookingException.mediaStorageUnavailable();
        }
    }

    private void validateBatch(UUID bookingId, List<MediaFile> files) {
        BookingProperties.Media cfg = properties.getMedia();
        long existing = mediaRepository.countByBookingId(bookingId);
        if (existing + files.size() > cfg.getMaxFiles()) {
            throw BookingException.media(
                    "A booking may have at most " + cfg.getMaxFiles() + " media files");
        }
        for (MediaFile file : files) {
            validateFile(file, cfg);
        }
    }

    private void validateFile(MediaFile file, BookingProperties.Media cfg) {
        if (file.contentType() == null || !cfg.getAllowedContentTypes().contains(file.contentType())) {
            throw BookingException.media(
                    "Unsupported media type '" + (file == null ? null : file.contentType())
                            + "'; allowed: " + cfg.getAllowedContentTypes());
        }
        if (file.sizeBytes() <= 0) {
            throw BookingException.media("Media file is empty");
        }
        if (file.sizeBytes() > cfg.getMaxFileSize()) {
            throw BookingException.media(
                    "Media file exceeds the maximum size of " + cfg.getMaxFileSize() + " bytes");
        }
    }
}
