import { useEffect, useMemo, useRef } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  ImageList,
  ImageListItem,
  Stack,
  Typography,
} from '@mui/material';
import AddAPhotoRoundedIcon from '@mui/icons-material/AddAPhotoRounded';
import CheckCircleRoundedIcon from '@mui/icons-material/CheckCircleRounded';
import PhotoLibraryRoundedIcon from '@mui/icons-material/PhotoLibraryRounded';
import { isApiError } from '@api/client';
import { env } from '@config/env';
import { fetchJobPhoto, type JobPhoto, type PhotoKind } from './api';
import { useUploadJobPhotos } from './hooks';

interface PhotoUploadCardProps {
  bookingId: string;
  kind: PhotoKind;
  title: string;
  /** Explains why the photo is required (Requirement 11.2 / 11.4). */
  helperText: string;
  /** Photos already attached to the booking, filtered to this kind by the parent. */
  photos: JobPhoto[];
  /** Whether uploads are currently allowed (based on job state). */
  disabled?: boolean;
}

/**
 * Reusable card for uploading and previewing before/after photos. Enforces
 * image-only selection at the input level; the Booking Service is the source of
 * truth for the "at least one photo" rule (Requirement 11.2, 11.4).
 *
 * In the Android and iOS apps a second button opens the camera directly
 * (Requirement MT-14.4): Android's file chooser offers no camera for a plain
 * file input, while one marked `capture` makes the WebView launch the camera
 * and ask for the camera permission. The web app keeps its single picker,
 * since `capture` would take the gallery choice away from mobile browsers.
 */
export function PhotoUploadCard({
  bookingId,
  kind,
  title,
  helperText,
  photos,
  disabled = false,
}: PhotoUploadCardProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const cameraRef = useRef<HTMLInputElement>(null);
  const upload = useUploadJobPhotos(bookingId);

  const handleFiles = (fileList: FileList | null) => {
    if (!fileList || fileList.length === 0) return;
    upload.mutate({ kind, files: Array.from(fileList) });
    if (inputRef.current) inputRef.current.value = '';
    if (cameraRef.current) cameraRef.current.value = '';
  };

  const hasPhotos = photos.length > 0;

  return (
    <Card variant="outlined">
      <CardContent>
        <Stack
          direction="row"
          spacing={1}
          sx={{
            justifyContent: 'space-between',
            alignItems: 'center',
          }}
        >
          <Typography
            variant="subtitle2"
            sx={{
              fontWeight: 700,
            }}
          >
            {title}
          </Typography>
          {hasPhotos ? (
            <CheckCircleRoundedIcon color="success" fontSize="small" aria-label="Photo attached" />
          ) : null}
        </Stack>
        <Typography
          variant="body2"
          sx={{
            color: 'text.secondary',
            mt: 0.5,
          }}
        >
          {helperText}
        </Typography>

        {hasPhotos ? (
          <ImageList cols={3} gap={8} sx={{ mt: 1.5, mb: 0 }}>
            {photos.map((photo) => (
              <ImageListItem key={photo.id}>
                <StoredPhoto bookingId={bookingId} photo={photo} kind={kind} />
              </ImageListItem>
            ))}
          </ImageList>
        ) : null}

        {upload.isError ? (
          <Alert severity="error" sx={{ mt: 1.5 }}>
            {isApiError(upload.error) ? upload.error.message : 'Photo upload failed. Try again.'}
          </Alert>
        ) : null}

        <input
          ref={inputRef}
          type="file"
          accept="image/*"
          multiple
          hidden
          onChange={(event) => handleFiles(event.target.files)}
        />
        {env.isNative ? (
          <>
            <input
              ref={cameraRef}
              type="file"
              accept="image/*"
              capture="environment"
              hidden
              onChange={(event) => handleFiles(event.target.files)}
            />
            <Stack direction="row" spacing={1} sx={{ mt: 1.5 }}>
              <Button
                variant={hasPhotos ? 'outlined' : 'contained'}
                startIcon={<AddAPhotoRoundedIcon />}
                onClick={() => cameraRef.current?.click()}
                disabled={disabled || upload.isPending}
                fullWidth
              >
                {upload.isPending ? 'Uploading…' : 'Take photo'}
              </Button>
              <Button
                variant="outlined"
                startIcon={<PhotoLibraryRoundedIcon />}
                onClick={() => inputRef.current?.click()}
                disabled={disabled || upload.isPending}
                fullWidth
              >
                Choose
              </Button>
            </Stack>
          </>
        ) : (
          <Button
            variant={hasPhotos ? 'outlined' : 'contained'}
            startIcon={<AddAPhotoRoundedIcon />}
            onClick={() => inputRef.current?.click()}
            disabled={disabled || upload.isPending}
            sx={{ mt: 1.5 }}
            fullWidth
          >
            {upload.isPending ? 'Uploading…' : hasPhotos ? 'Add another photo' : 'Upload photo'}
          </Button>
        )}
      </CardContent>
    </Card>
  );
}

/**
 * One stored photo. Its bytes come from GET /bookings/{id}/photos/{photoId}, which needs the
 * bearer header, so it is fetched through the API client as a blob and shown from an object
 * URL rather than a plain <img src>. A photo with no stored bytes (uploads from before photos
 * were kept) or a failed read shows a tile saying a photo is on file.
 */
function StoredPhoto({
  bookingId,
  photo,
  kind,
}: {
  bookingId: string;
  photo: JobPhoto;
  kind: PhotoKind;
}) {
  const label = `${kind === 'BEFORE' ? 'Before' : 'After'} photo`;
  const blob = useQuery({
    queryKey: ['jobs', 'photo', bookingId, photo.id],
    queryFn: () => fetchJobPhoto(bookingId, photo.id),
    enabled: !photo.url,
    staleTime: Infinity,
    retry: false,
  });
  // One object URL per fetched blob, released when the blob changes or the tile unmounts.
  const objectUrl = useMemo(() => (blob.data ? URL.createObjectURL(blob.data) : null), [blob.data]);
  useEffect(
    () => () => {
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    },
    [objectUrl],
  );

  const src = photo.url ?? objectUrl;
  if (src) {
    return (
      <Box
        component="img"
        src={src}
        alt={label}
        loading="lazy"
        sx={{ borderRadius: 1, aspectRatio: '1 / 1', objectFit: 'cover' }}
      />
    );
  }
  return (
    <Box
      role="img"
      aria-label={`${label} uploaded`}
      aria-busy={blob.isLoading}
      sx={{
        borderRadius: 1,
        aspectRatio: '1 / 1',
        bgcolor: 'action.hover',
        display: 'grid',
        placeItems: 'center',
      }}
    >
      <CheckCircleRoundedIcon color="success" />
    </Box>
  );
}
