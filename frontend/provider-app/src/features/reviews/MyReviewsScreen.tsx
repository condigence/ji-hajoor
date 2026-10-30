import { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Rating,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { AppShell } from '@components/AppShell';
import { QueryStateView } from '@components/QueryStateView';
import { MAX_REPLY_LENGTH, type ReceivedReview } from './api';
import { useReceivedReviews, useReply } from './hooks';
import { ShareProfileCard } from './ShareProfileCard';

function formatDay(iso: string): string {
  return new Date(iso).toLocaleDateString('en-IN', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  });
}

/**
 * The reviews customers left you. Answer each one publicly: a polite reply,
 * especially to a critical review, shows future customers how you work.
 */
export function MyReviewsScreen() {
  const reviews = useReceivedReviews();
  return (
    <AppShell title="My reviews" onBack={() => window.history.back()}>
      <Stack spacing={2}>
        <ShareProfileCard />
        <QueryStateView
          isLoading={reviews.isLoading}
          isError={reviews.isError}
          error={reviews.error}
          onRetry={() => void reviews.refetch()}
          isEmpty={(reviews.data ?? []).length === 0}
          emptyMessage="Reviews from your customers will appear here."
        >
          <Stack spacing={1.5}>
            {(reviews.data ?? []).map((review) => (
              <ReviewCard key={review.id} review={review} />
            ))}
          </Stack>
        </QueryStateView>
      </Stack>
    </AppShell>
  );
}

function ReviewCard({ review }: { review: ReceivedReview }) {
  const reply = useReply();
  const [editing, setEditing] = useState(false);
  const [text, setText] = useState(review.reply ?? '');
  const trimmed = text.trim();

  return (
    <Card>
      <CardContent>
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
          <Rating value={review.overall} readOnly size="small" />
          <Typography variant="caption" sx={{ color: 'text.secondary' }}>
            Verified customer · {formatDay(review.submittedAt)}
          </Typography>
        </Stack>
        <Typography variant="body2" sx={{ mt: 1, whiteSpace: 'pre-line' }}>
          {review.text || 'No comment, just a rating.'}
        </Typography>

        {review.reply && !editing ? (
          <Box sx={{ mt: 1.5, pl: 1.5, borderLeft: 3, borderColor: 'divider' }}>
            <Typography variant="caption" sx={{ fontWeight: 700 }}>
              Your reply
            </Typography>
            <Typography variant="body2" sx={{ whiteSpace: 'pre-line' }}>
              {review.reply}
            </Typography>
            <Button size="small" onClick={() => setEditing(true)} sx={{ mt: 0.5, px: 0 }}>
              Edit reply
            </Button>
          </Box>
        ) : null}

        {!review.reply || editing ? (
          <Stack spacing={1} sx={{ mt: 1.5 }}>
            <TextField
              label="Reply publicly"
              placeholder="Thank the customer, or explain what you will do better."
              multiline
              minRows={2}
              value={text}
              onChange={(event) => setText(event.target.value.slice(0, MAX_REPLY_LENGTH))}
              helperText={`${text.length}/${MAX_REPLY_LENGTH} · customers see this on your profile`}
            />
            {reply.error ? <Alert severity="error">{reply.error.message}</Alert> : null}
            <Stack direction="row" spacing={1}>
              <Button
                variant="contained"
                size="small"
                disabled={!trimmed || reply.isPending}
                onClick={() =>
                  reply.mutate(
                    { reviewId: review.id, text: trimmed },
                    { onSuccess: () => setEditing(false) },
                  )
                }
              >
                {reply.isPending ? 'Posting…' : 'Post reply'}
              </Button>
              {editing ? (
                <Button size="small" color="inherit" onClick={() => setEditing(false)}>
                  Cancel
                </Button>
              ) : null}
            </Stack>
          </Stack>
        ) : null}
      </CardContent>
    </Card>
  );
}
