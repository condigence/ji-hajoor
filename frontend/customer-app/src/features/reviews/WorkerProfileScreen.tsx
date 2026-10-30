import {
  Avatar,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Divider,
  LinearProgress,
  Rating,
  Stack,
  Typography,
} from '@mui/material';
import VerifiedRoundedIcon from '@mui/icons-material/VerifiedRounded';
import { useNavigate, useParams } from 'react-router';
import { AppShell } from '@components/AppShell';
import { QueryStateView } from '@components/QueryStateView';
import { formatDate } from '@lib/format';
import type { PublicReview, RatingSummary } from './api';
import { usePublicReviews, usePublicWorker } from './hooks';

const ASPECTS: Array<{ key: keyof RatingSummary; label: string }> = [
  { key: 'quality', label: 'Quality of work' },
  { key: 'timeliness', label: 'On time' },
  { key: 'behavior', label: 'Polite and professional' },
  { key: 'pricingTransparency', label: 'Fair, clear pricing' },
];

function memberSince(value?: string | null): string | null {
  if (!value) return null;
  const [year, month] = value.split('-').map(Number);
  if (!year || !month) return null;
  return new Date(Date.UTC(year, month - 1, 1)).toLocaleDateString('en-IN', {
    month: 'long',
    year: 'numeric',
  });
}

/**
 * A verified professional's public page: who they are, what customers said
 * (verified bookings only) and how they answered. Anyone can open it from a
 * link the professional shares; no sign-in needed.
 */
export function WorkerProfileScreen() {
  const { workerId = '' } = useParams();
  const navigate = useNavigate();
  const worker = usePublicWorker(workerId);
  const reviews = usePublicReviews(workerId);
  const summary = reviews.data?.pages[0]?.summary;
  const items = reviews.data?.pages.flatMap((page) => page.reviews) ?? [];
  const notFound = worker.isError && worker.error.status === 404;

  return (
    <AppShell title="Professional">
      <QueryStateView
        isLoading={worker.isLoading}
        isError={worker.isError && !notFound}
        error={worker.error}
        onRetry={() => void worker.refetch()}
        isEmpty={notFound}
        emptyTitle="Professional not found"
        emptyMessage="This page is not available. The professional may no longer be listed on Ji-Hajoor."
      >
        {worker.data ? (
          <Stack spacing={2.5} sx={{ maxWidth: 760, mx: 'auto' }}>
            <Card>
              <CardContent>
                <Stack direction="row" spacing={2} sx={{ alignItems: 'center' }}>
                  <Avatar sx={{ width: 64, height: 64, bgcolor: 'primary.main', fontSize: 26 }}>
                    {worker.data.displayName.slice(0, 1).toUpperCase()}
                  </Avatar>
                  <Box sx={{ minWidth: 0 }}>
                    <Typography variant="h4" component="h1">
                      {worker.data.displayName}
                    </Typography>
                    <Stack
                      direction="row"
                      spacing={1}
                      useFlexGap
                      sx={{ flexWrap: 'wrap', mt: 0.5 }}
                    >
                      <Chip
                        size="small"
                        color="success"
                        icon={<VerifiedRoundedIcon />}
                        label="Verified by Ji-Hajoor"
                      />
                      {worker.data.agencyName ? (
                        <Chip size="small" label={`With ${worker.data.agencyName}`} />
                      ) : null}
                    </Stack>
                  </Box>
                </Stack>
                <Typography variant="body2" sx={{ color: 'text.secondary', mt: 2 }}>
                  {[
                    worker.data.yearsExperience > 0
                      ? `${worker.data.yearsExperience} year${worker.data.yearsExperience === 1 ? '' : 's'} of experience`
                      : null,
                    memberSince(worker.data.memberSince)
                      ? `On Ji-Hajoor since ${memberSince(worker.data.memberSince)}`
                      : null,
                  ]
                    .filter(Boolean)
                    .join(' · ')}
                </Typography>
                {worker.data.skills.length ? (
                  <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap', mt: 1.5 }}>
                    {worker.data.skills.map((skill) => (
                      <Chip key={skill} label={skill} variant="outlined" size="small" />
                    ))}
                  </Stack>
                ) : null}
                <Button variant="contained" sx={{ mt: 2.5 }} onClick={() => void navigate('/home')}>
                  Book a service
                </Button>
              </CardContent>
            </Card>

            <Card>
              <CardContent>
                <Typography variant="h5" component="h2">
                  Customer reviews
                </Typography>
                {summary && summary.count > 0 ? (
                  <SummaryBlock summary={summary} />
                ) : (
                  <Typography variant="body2" sx={{ color: 'text.secondary', mt: 1 }}>
                    No reviews yet. Every review here comes from a completed, paid booking.
                  </Typography>
                )}
                <Stack divider={<Divider flexItem />} spacing={2} sx={{ mt: 2 }}>
                  {items.map((review) => (
                    <ReviewItem
                      key={review.id}
                      review={review}
                      workerName={worker.data.displayName}
                    />
                  ))}
                </Stack>
                {reviews.hasNextPage ? (
                  <Button
                    sx={{ mt: 2 }}
                    onClick={() => void reviews.fetchNextPage()}
                    disabled={reviews.isFetchingNextPage}
                  >
                    {reviews.isFetchingNextPage ? 'Loading…' : 'Show more reviews'}
                  </Button>
                ) : null}
              </CardContent>
            </Card>
          </Stack>
        ) : null}
      </QueryStateView>
    </AppShell>
  );
}

function SummaryBlock({ summary }: { summary: RatingSummary }) {
  const average = summary.average ?? 0;
  return (
    <Box
      sx={{
        display: 'grid',
        gridTemplateColumns: { xs: '1fr', sm: '160px 1fr' },
        gap: 2,
        mt: 1.5,
      }}
    >
      <Box>
        <Typography variant="h2" component="p">
          {average.toFixed(1)}
        </Typography>
        <Rating value={average} precision={0.1} readOnly size="small" />
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          {summary.count} verified review{summary.count === 1 ? '' : 's'}
        </Typography>
      </Box>
      <Stack spacing={0.5}>
        {[5, 4, 3, 2, 1].map((stars) => {
          const count = summary.distribution[String(stars)] ?? 0;
          return (
            <Stack key={stars} direction="row" spacing={1} sx={{ alignItems: 'center' }}>
              <Typography variant="caption" sx={{ width: 16 }}>
                {stars}
              </Typography>
              <LinearProgress
                variant="determinate"
                value={summary.count ? (count / summary.count) * 100 : 0}
                sx={{ flexGrow: 1, height: 6, borderRadius: 3 }}
                aria-label={`${count} ${stars}-star reviews`}
              />
              <Typography variant="caption" sx={{ width: 24, textAlign: 'right' }}>
                {count}
              </Typography>
            </Stack>
          );
        })}
        {ASPECTS.some((aspect) => summary[aspect.key] != null) ? (
          <Stack direction="row" spacing={2} useFlexGap sx={{ flexWrap: 'wrap', pt: 1 }}>
            {ASPECTS.map((aspect) =>
              summary[aspect.key] != null ? (
                <Typography key={aspect.key} variant="caption" sx={{ color: 'text.secondary' }}>
                  {aspect.label}: <strong>{Number(summary[aspect.key]).toFixed(1)}</strong>
                </Typography>
              ) : null,
            )}
          </Stack>
        ) : null}
      </Stack>
    </Box>
  );
}

function ReviewItem({ review, workerName }: { review: PublicReview; workerName: string }) {
  return (
    <Box>
      <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
        <Rating value={review.overall} readOnly size="small" />
        <Typography variant="caption" sx={{ color: 'text.secondary' }}>
          Verified customer · {formatDate(review.submittedAt)}
        </Typography>
      </Stack>
      {review.text ? (
        <Typography variant="body2" sx={{ mt: 0.75, whiteSpace: 'pre-line' }}>
          {review.text}
        </Typography>
      ) : null}
      {review.reply ? (
        <Box sx={{ mt: 1, pl: 1.5, borderLeft: 3, borderColor: 'divider' }}>
          <Typography variant="caption" sx={{ fontWeight: 700 }}>
            Response from {workerName}
          </Typography>
          <Typography variant="body2" sx={{ whiteSpace: 'pre-line' }}>
            {review.reply}
          </Typography>
        </Box>
      ) : null}
    </Box>
  );
}
