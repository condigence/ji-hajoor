import { useState } from 'react';
import { Alert, Box, Button, Collapse, Rating, Stack, TextField, Typography } from '@mui/material';
import StarRoundedIcon from '@mui/icons-material/StarRounded';
import { useMyPrompts, useSubmitReview } from './hooks';

const DIMENSIONS = [
  { key: 'quality', label: 'Quality of work' },
  { key: 'timeliness', label: 'On time' },
  { key: 'behavior', label: 'Polite and professional' },
  { key: 'pricingTransparency', label: 'Fair, clear pricing' },
] as const;

type DimensionKey = (typeof DIMENSIONS)[number]['key'];

const MAX_TEXT = 1000;

interface RateJobCardProps {
  bookingId: string;
  workerName?: string;
}

/**
 * "How was your job?" on a paid booking: overall stars first (the part most
 * people give), then four quick aspects pre-filled from it and an optional note.
 * Shows the rating once given; nothing when the booking has no review prompt
 * (not paid yet, or the review window closed).
 */
export function RateJobCard({ bookingId, workerName }: RateJobCardProps) {
  const prompts = useMyPrompts();
  const submit = useSubmitReview();
  const prompt = prompts.data?.find(
    (candidate) => candidate.bookingId === bookingId && candidate.role === 'CUSTOMER',
  );

  const [overall, setOverall] = useState<number | null>(null);
  const [aspects, setAspects] = useState<Partial<Record<DimensionKey, number>>>({});
  const [text, setText] = useState('');

  if (!prompt || prompt.status === 'EXPIRED') return null;

  if (prompt.status === 'REVIEWED' || submit.isSuccess) {
    const stars = prompt.myRating ?? overall ?? 0;
    return (
      <Box
        sx={{
          p: 2,
          borderRadius: 2,
          bgcolor: 'success.50',
          border: 1,
          borderColor: 'success.light',
        }}
      >
        <Typography variant="subtitle2">Thanks for your review</Typography>
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mt: 0.5 }}>
          <Rating value={stars} readOnly size="small" aria-label={`You rated ${stars} out of 5`} />
          <Typography variant="body2" sx={{ color: 'text.secondary' }}>
            It helps other customers choose, and good professionals get more work.
          </Typography>
        </Stack>
      </Box>
    );
  }

  const valueOf = (key: DimensionKey) => aspects[key] ?? overall ?? 0;
  const send = () => {
    if (!overall) return;
    submit.mutate({
      bookingId,
      overall,
      quality: valueOf('quality'),
      timeliness: valueOf('timeliness'),
      behavior: valueOf('behavior'),
      pricingTransparency: valueOf('pricingTransparency'),
      ...(text.trim() ? { reviewText: text.trim() } : {}),
    });
  };

  return (
    <Box sx={{ p: 2, borderRadius: 2, border: 1, borderColor: 'divider' }}>
      <Typography variant="subtitle1" component="h3" sx={{ fontWeight: 700 }}>
        How was your job{workerName ? ` with ${workerName}` : ''}?
      </Typography>
      <Rating
        name={`overall-${bookingId}`}
        value={overall}
        onChange={(_, value) => setOverall(value)}
        size="large"
        sx={{ mt: 1 }}
        emptyIcon={<StarRoundedIcon fontSize="inherit" />}
        icon={<StarRoundedIcon fontSize="inherit" />}
        aria-label="Overall rating"
      />
      <Collapse in={Boolean(overall)} unmountOnExit>
        <Stack spacing={1} sx={{ mt: 1.5 }}>
          {DIMENSIONS.map((dimension) => (
            <Stack
              key={dimension.key}
              direction="row"
              sx={{ justifyContent: 'space-between', alignItems: 'center' }}
            >
              <Typography variant="body2">{dimension.label}</Typography>
              <Rating
                name={`${dimension.key}-${bookingId}`}
                value={valueOf(dimension.key)}
                onChange={(_, value) =>
                  setAspects((current) => ({ ...current, [dimension.key]: value ?? undefined }))
                }
                size="small"
                aria-label={dimension.label}
              />
            </Stack>
          ))}
          <TextField
            label="Tell others about the job (optional)"
            multiline
            minRows={2}
            value={text}
            onChange={(event) => setText(event.target.value.slice(0, MAX_TEXT))}
            helperText={`${text.length}/${MAX_TEXT}`}
          />
          {submit.error ? <Alert severity="error">{submit.error.message}</Alert> : null}
          <Button variant="contained" onClick={send} disabled={submit.isPending}>
            {submit.isPending ? 'Sending…' : 'Submit review'}
          </Button>
        </Stack>
      </Collapse>
    </Box>
  );
}
