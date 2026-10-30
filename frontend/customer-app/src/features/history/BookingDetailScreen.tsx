import { useEffect, useMemo, type ReactNode } from 'react';
import { useNavigate, useParams } from 'react-router';
import {
  Alert,
  Avatar,
  Box,
  Button,
  Card,
  CardContent,
  Divider,
  ImageList,
  ImageListItem,
  Link,
  Stack,
  Typography,
} from '@mui/material';
import DownloadRoundedIcon from '@mui/icons-material/DownloadRounded';
import VerifiedRoundedIcon from '@mui/icons-material/VerifiedRounded';
import StarRounded from '@mui/icons-material/StarRounded';
import EventRoundedIcon from '@mui/icons-material/EventRounded';
import PlaceRoundedIcon from '@mui/icons-material/PlaceRounded';
import NotesRoundedIcon from '@mui/icons-material/NotesRounded';
import TagRoundedIcon from '@mui/icons-material/TagRounded';
import BoltRoundedIcon from '@mui/icons-material/BoltRounded';
import NearMeRoundedIcon from '@mui/icons-material/NearMeRounded';
import ReplayRoundedIcon from '@mui/icons-material/ReplayRounded';
import SearchOffRoundedIcon from '@mui/icons-material/SearchOffRounded';
import HandymanRoundedIcon from '@mui/icons-material/HandymanRounded';
import PhotoCameraRoundedIcon from '@mui/icons-material/PhotoCameraRounded';
import { Link as RouterLink } from 'react-router';
import { AppShell } from '@components/AppShell';
import { QueryStateView } from '@components/QueryStateView';
import { EmptyState, IconTile } from '@components/StateViews';
import { ProgressTimeline } from '@components/ProgressTimeline';
import { friendlyErrorMessage } from '@api/client';
import { formatCurrency, formatDateTime } from '@lib/format';
import { brand, radius } from '@lib/theme';
import { CategoryIcon } from '@features/catalog/categoryIcon';
import { categoryArt } from '@features/catalog/categoryArt';
import { categoryIconKey } from '@features/catalog/api';
import { isPayableStatus } from '@features/payment/api';
import { PartsList, PaymentCardActions } from '@features/payment/PaymentPanel';
import { ChatButton } from '@features/tracking/ChatButton';
import { CHAT_STATUSES, journeySteps } from '@features/tracking/progress';
import type { BookingDetail, JobPhoto, PhotoKind } from './api';
import { useBookingDetail, useBookingInvoice, useInvoiceDownload, useJobPhoto } from './hooks';
import { StatusChip } from './StatusChip';
import { isTerminalStatus } from './status';
import { RateJobCard } from '@features/reviews/RateJobCard';
import { useBookingService } from './useServiceName';

/**
 * Booking Detail screen (Requirement 28.7). Reached from Service History. Shows
 * the booking, its progress and the professional's before/after photos, and —
 * once the booking is paid and its invoice generated — lets the Customer
 * download the invoice PDF (Requirement 13). Active
 * bookings also link to live tracking and the in-app chat, and a completed job
 * that is still unpaid can be paid from here (Requirement 12).
 */
export function BookingDetailScreen() {
  const { bookingId = '' } = useParams();
  const navigate = useNavigate();

  const query = useBookingDetail(bookingId);
  const notFound = query.isError && (query.error.status === 404 || query.error.status === 403);

  return (
    <AppShell
      title="Booking details"
      {...(query.data ? { subtitle: `Reference ${query.data.referenceNumber}` } : {})}
      onBack={() => void navigate('/history')}
      width="full"
    >
      {notFound ? (
        <BookingNotFound onBack={() => void navigate('/history')} />
      ) : (
        <QueryStateView
          isLoading={query.isLoading}
          isError={query.isError}
          error={query.error}
          onRetry={() => void query.refetch()}
          isEmpty={!query.data}
          emptyMessage="We couldn't find this booking."
          skeletonRows={3}
          skeletonHeight={120}
        >
          {query.data ? (
            <BookingDetailBody
              detail={query.data}
              onTrack={() => void navigate(`/bookings/${bookingId}/track`)}
              onChat={() => void navigate(`/bookings/${bookingId}/chat`)}
              onBookAgain={(subcategoryId) => void navigate(`/book/${subcategoryId}`)}
            />
          ) : null}
        </QueryStateView>
      )}
    </AppShell>
  );
}

/** Friendly dead end for a booking that does not exist or is not the caller's. */
export function BookingNotFound({ onBack }: { onBack: () => void }) {
  return (
    <EmptyState
      icon={<SearchOffRoundedIcon />}
      title="We couldn't find that booking"
      description="It may have been removed, or it belongs to a different account."
      action={
        <Button variant="contained" size="large" onClick={onBack}>
          Go to your bookings
        </Button>
      }
    />
  );
}

function BookingDetailBody({
  detail,
  onTrack,
  onChat,
  onBookAgain,
}: {
  detail: BookingDetail;
  onTrack: () => void;
  onChat: () => void;
  onBookAgain: (subcategoryId: string) => void;
}) {
  const { name, category } = useBookingService(detail.serviceName, detail.subcategoryId);
  const art = categoryArt(categoryIconKey(category), category?.name);
  // The invoice lives in the Invoice Service, not on the booking detail.
  const invoice = useBookingInvoice(detail.bookingId, detail.status).data;
  const invoiceDownload = useInvoiceDownload();
  const invoiceError = invoiceDownload.isError
    ? friendlyErrorMessage(invoiceDownload.error, INVOICE_DOWNLOAD_FAILED)
    : null;
  const photos = detail.photos ?? [];
  const terminal = isTerminalStatus(detail.status);
  const canChat = CHAT_STATUSES.includes(detail.status);
  const when = detail.scheduledAt ?? (detail.emergency ? undefined : detail.date);
  // A completed, unpaid job: paying is the screen's primary action, so the
  // summary card leads with it (and comes first on a phone).
  const payable = isPayableStatus(detail.status);
  const paid = detail.status === 'PAYMENT_COMPLETED';

  return (
    <Box
      sx={{
        display: 'grid',
        gridTemplateColumns: { xs: '1fr', md: 'minmax(0, 1fr) 360px' },
        gridTemplateAreas: {
          xs: payable
            ? '"actions" "summary" "progress" "photos"'
            : '"summary" "actions" "progress" "photos"',
          md: '"summary actions" "progress actions" "photos actions"',
        },
        gap: { xs: 2, md: 3 },
        alignItems: 'start',
      }}
    >
      <Card sx={{ gridArea: 'summary' }}>
        <CardContent>
          <Stack
            direction="row"
            spacing={2}
            sx={{
              alignItems: 'flex-start',
            }}
          >
            <IconTile size={52} bg={art.wash} color={art.accent}>
              {category ? (
                <CategoryIcon iconKey={categoryIconKey(category)} categoryName={category.name} />
              ) : (
                <HandymanRoundedIcon />
              )}
            </IconTile>
            <Box sx={{ minWidth: 0, flexGrow: 1 }}>
              <Typography variant="h4" component="h2">
                {name ?? 'Your booking'}
              </Typography>
              <Stack
                direction="row"
                spacing={1}
                useFlexGap
                sx={{
                  alignItems: 'center',
                  flexWrap: 'wrap',
                  mt: 1,
                }}
              >
                <StatusChip status={detail.status} size="medium" />
                {detail.emergency ? <EmergencyTag /> : null}
              </Stack>
            </Box>
          </Stack>

          <Divider sx={{ my: { xs: 2, md: 2.5 } }} />

          <Stack spacing={2}>
            <DetailRow
              icon={<EventRoundedIcon />}
              label={detail.emergency && !detail.scheduledAt ? 'Requested' : 'Scheduled for'}
              value={when ? formatDateTime(when) : 'As soon as possible'}
            />
            {detail.createdAt ? (
              <DetailRow
                icon={<EventRoundedIcon />}
                label="Booked on"
                value={formatDateTime(detail.createdAt)}
              />
            ) : null}
            <DetailRow icon={<TagRoundedIcon />} label="Reference" value={detail.referenceNumber} />
            {detail.address ? (
              <DetailRow icon={<PlaceRoundedIcon />} label="Address" value={detail.address} />
            ) : null}
            {detail.description ? (
              <DetailRow
                icon={<NotesRoundedIcon />}
                label="Your notes"
                value={detail.description}
              />
            ) : null}
          </Stack>

          {detail.provider ? (
            <>
              <Divider sx={{ my: { xs: 2, md: 2.5 } }} />
              <ProviderRow
                provider={detail.provider}
                {...(detail.providerId ? { profileHref: `/workers/${detail.providerId}` } : {})}
              />
            </>
          ) : null}
        </CardContent>
      </Card>

      <Card sx={{ gridArea: 'actions', position: { md: 'sticky' }, top: { md: 96 } }}>
        <CardContent>
          <Typography
            variant="body2"
            sx={{
              color: 'text.secondary',
            }}
          >
            {paid ? 'Paid' : payable ? 'Total to pay' : terminal ? 'Amount' : 'Estimated total'}
          </Typography>
          <Typography variant="h2" component="p" sx={{ mt: 0.25 }}>
            {formatCurrency(detail.amount, detail.currency)}
          </Typography>
          {payable && detail.parts?.length ? (
            <Typography
              variant="body2"
              component="div"
              sx={{
                color: 'text.secondary',
                mt: 1,
                display: 'flex',
                flexDirection: 'column',
                gap: 0.25,
              }}
            >
              <PartsList detail={detail} />
            </Typography>
          ) : null}
          {paid ? (
            <Typography
              variant="caption"
              sx={{
                color: 'success.dark',
                display: 'block',
                mt: 0.5,
              }}
            >
              Payment received — thank you.
            </Typography>
          ) : null}
          {terminal || payable ? null : (
            <Typography
              variant="caption"
              sx={{
                color: 'text.secondary',
                display: 'block',
                mt: 0.5,
              }}
            >
              You approve any change before it is charged.
            </Typography>
          )}

          <Stack spacing={1.25} sx={{ mt: 2.5 }}>
            {paid ? (
              <RateJobCard
                bookingId={detail.bookingId}
                {...(detail.provider ? { workerName: detail.provider.displayName } : {})}
              />
            ) : null}
            {payable ? <PaymentCardActions detail={detail} /> : null}
            {terminal ? null : (
              <Button
                variant={payable ? 'outlined' : 'contained'}
                size="large"
                fullWidth
                startIcon={<NearMeRoundedIcon />}
                onClick={onTrack}
              >
                Track booking
              </Button>
            )}
            {canChat ? <ChatButton bookingId={detail.bookingId} onOpen={onChat} /> : null}

            {invoice ? (
              <>
                {invoiceError ? <Alert severity="error">{invoiceError}</Alert> : null}
                <Button
                  variant={terminal ? 'contained' : 'outlined'}
                  fullWidth
                  size="large"
                  startIcon={<DownloadRoundedIcon />}
                  onClick={() => invoiceDownload.mutate(invoice)}
                  disabled={invoiceDownload.isPending}
                  aria-label={`Download invoice ${invoice.invoiceNumber}`}
                >
                  {invoiceDownload.isPending ? 'Preparing…' : 'Download invoice'}
                </Button>
              </>
            ) : null}

            {terminal && detail.subcategoryId ? (
              <Button
                variant={invoice ? 'outlined' : 'contained'}
                size="large"
                fullWidth
                startIcon={<ReplayRoundedIcon />}
                onClick={() => detail.subcategoryId && onBookAgain(detail.subcategoryId)}
              >
                Book again
              </Button>
            ) : null}
          </Stack>

          <Typography
            variant="body2"
            sx={{
              color: 'text.secondary',
              mt: 2.5,
            }}
          >
            Something not right?{' '}
            <Link
              component={RouterLink}
              to="/help"
              underline="hover"
              sx={{
                fontWeight: 600,
              }}
            >
              Get help
            </Link>
          </Typography>
        </CardContent>
      </Card>

      <Card sx={{ gridArea: 'progress' }}>
        <CardContent>
          <Typography variant="h5" component="h2" sx={{ mb: 2.5 }}>
            Progress
          </Typography>
          <ProgressTimeline steps={journeySteps(detail.status)} />
        </CardContent>
      </Card>

      {photos.length ? (
        <Card sx={{ gridArea: 'photos' }}>
          <CardContent>
            <Typography variant="h5" component="h2" sx={{ mb: 2 }}>
              Job photos
            </Typography>
            <Stack spacing={2.5}>
              <PhotoGroup bookingId={detail.bookingId} kind="BEFORE" photos={photos} />
              <PhotoGroup bookingId={detail.bookingId} kind="AFTER" photos={photos} />
            </Stack>
          </CardContent>
        </Card>
      ) : null}
    </Box>
  );
}

const INVOICE_DOWNLOAD_FAILED = "We couldn't download the invoice. Please try again.";

const PHOTO_KIND_LABEL: Record<PhotoKind, string> = { BEFORE: 'Before', AFTER: 'After' };

/** The professional's photos of one kind, as a grid of tiles; nothing when there are none. */
function PhotoGroup({
  bookingId,
  kind,
  photos,
}: {
  bookingId: string;
  kind: PhotoKind;
  photos: JobPhoto[];
}) {
  const ofKind = photos.filter((photo) => photo.kind === kind);
  if (!ofKind.length) return null;
  return (
    <Box>
      <Typography variant="subtitle2" component="h3" sx={{ color: 'text.secondary', mb: 1 }}>
        {PHOTO_KIND_LABEL[kind]} the job
      </Typography>
      <ImageList cols={3} gap={8} sx={{ m: 0 }}>
        {ofKind.map((photo) => (
          <ImageListItem key={photo.id}>
            <StoredPhoto bookingId={bookingId} photo={photo} />
          </ImageListItem>
        ))}
      </ImageList>
    </Box>
  );
}

/**
 * One stored photo. Its bytes come from GET /bookings/{key}/photos/{photoId},
 * which needs the bearer header, so it is fetched through the API client as a
 * blob and shown from an object URL rather than a plain <img src>. A photo with
 * no stored bytes (404 MEDIA_NOT_FOUND: uploaded before photos were kept) or a
 * failed read shows a tile saying a photo is on file.
 */
function StoredPhoto({ bookingId, photo }: { bookingId: string; photo: JobPhoto }) {
  const label = `${PHOTO_KIND_LABEL[photo.kind]} photo`;
  const blob = useJobPhoto(bookingId, photo.id);
  // One object URL per fetched blob, released when the blob changes or the tile unmounts.
  const objectUrl = useMemo(() => (blob.data ? URL.createObjectURL(blob.data) : null), [blob.data]);
  useEffect(
    () => () => {
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    },
    [objectUrl],
  );

  if (objectUrl) {
    return (
      <Box
        component="img"
        src={objectUrl}
        alt={label}
        loading="lazy"
        sx={{ borderRadius: 1, aspectRatio: '1 / 1', objectFit: 'cover', width: '100%' }}
      />
    );
  }
  return (
    <Box
      role="img"
      aria-label={blob.isError ? `${label} on file` : `Loading ${label.toLowerCase()}`}
      aria-busy={blob.isLoading}
      sx={{
        borderRadius: 1,
        aspectRatio: '1 / 1',
        bgcolor: 'action.hover',
        color: 'text.secondary',
        display: 'grid',
        placeItems: 'center',
      }}
    >
      <PhotoCameraRoundedIcon />
    </Box>
  );
}

function EmergencyTag() {
  return (
    <Stack
      direction="row"
      spacing={0.5}
      sx={{
        alignItems: 'center',
        px: 1.25,
        py: 0.5,
        borderRadius: `${radius.pill}px`,
        bgcolor: brand.redSoft,
        color: brand.redDark,
      }}
    >
      <BoltRoundedIcon sx={{ fontSize: 15 }} aria-hidden />
      <Typography
        variant="caption"
        sx={{
          fontWeight: 700,
        }}
      >
        Emergency
      </Typography>
    </Stack>
  );
}

function DetailRow({ icon, label, value }: { icon: ReactNode; label: string; value: string }) {
  return (
    <Stack
      direction="row"
      spacing={1.5}
      sx={{
        alignItems: 'flex-start',
      }}
    >
      <Box
        sx={{ color: 'text.secondary', display: 'flex', pt: 0.25, '& svg': { fontSize: 20 } }}
        aria-hidden
      >
        {icon}
      </Box>
      <Box sx={{ minWidth: 0 }}>
        <Typography
          variant="caption"
          sx={{
            color: 'text.secondary',
            display: 'block',
          }}
        >
          {label}
        </Typography>
        <Typography
          variant="body1"
          sx={{
            fontWeight: 600,
            wordBreak: 'break-word',
          }}
        >
          {value}
        </Typography>
      </Box>
    </Stack>
  );
}

function ProviderRow({
  provider,
  profileHref,
}: {
  provider: NonNullable<BookingDetail['provider']>;
  profileHref?: string;
}) {
  const initials = provider.displayName
    .split(' ')
    .map((part) => part.charAt(0))
    .slice(0, 2)
    .join('')
    .toUpperCase();
  return (
    <Stack
      direction="row"
      spacing={1.5}
      sx={{
        alignItems: 'center',
      }}
    >
      <Avatar
        aria-hidden
        sx={{ width: 44, height: 44, bgcolor: brand.accentSoft, color: 'primary.main' }}
      >
        {initials}
      </Avatar>
      <Box sx={{ flexGrow: 1, minWidth: 0 }}>
        <Typography
          variant="caption"
          sx={{
            color: 'text.secondary',
            display: 'block',
          }}
        >
          Your professional
        </Typography>
        <Stack
          direction="row"
          spacing={0.5}
          sx={{
            alignItems: 'center',
          }}
        >
          <Typography
            variant="subtitle1"
            noWrap
            sx={{
              fontWeight: 700,
            }}
          >
            {provider.displayName}
          </Typography>
          {provider.verified ? (
            <VerifiedRoundedIcon
              sx={{ fontSize: 18, color: 'success.main' }}
              aria-label="Verified"
            />
          ) : null}
        </Stack>
        {profileHref && provider.verified ? (
          <Link component={RouterLink} to={profileHref} variant="caption" underline="hover">
            See profile and reviews
          </Link>
        ) : null}
      </Box>
      <Stack
        direction="row"
        spacing={0.25}
        sx={{
          alignItems: 'center',
        }}
      >
        <StarRounded sx={{ fontSize: 18, color: brand.warm }} aria-hidden />
        <Typography
          variant="body2"
          sx={{
            fontWeight: 700,
          }}
        >
          {provider.rating.toFixed(1)}
        </Typography>
      </Stack>
    </Stack>
  );
}
