import { Alert, Button } from '@mui/material';
import { useNavigate } from 'react-router';
import { useOpenPrompts } from './hooks';

/**
 * A gentle nudge on the home screen while a paid job is waiting for a rating;
 * opens that booking, where the rating card is.
 */
export function PendingReviewsBanner() {
  const navigate = useNavigate();
  const open = useOpenPrompts();
  const first = open[0];
  if (!first) return null;
  return (
    <Alert
      severity="info"
      sx={{ mb: 3 }}
      action={
        <Button
          color="inherit"
          size="small"
          onClick={() => void navigate(`/bookings/${first.bookingId}`)}
        >
          Rate now
        </Button>
      }
    >
      {open.length === 1
        ? 'How was your recent job? A quick rating helps other customers.'
        : `You have ${open.length} jobs to rate. A quick rating helps other customers.`}
    </Alert>
  );
}
