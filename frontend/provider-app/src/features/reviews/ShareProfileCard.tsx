import { useState } from 'react';
import { Alert, Button, Card, CardContent, Rating, Stack, Typography } from '@mui/material';
import ShareRoundedIcon from '@mui/icons-material/ShareRounded';
import StarRoundedIcon from '@mui/icons-material/StarRounded';
import { useNavigate } from 'react-router';
import { useAuthStore } from '@stores/authStore';
import { profileLink } from './api';
import { useCustomerAppUrl, useMySummary } from './hooks';

/**
 * Your rating at a glance, a way into your reviews, and a link to share your
 * public page (on WhatsApp, a visiting card, a shop sign) so customers who meet
 * you can see your verified reviews and book on Ji-Hajoor.
 */
export function ShareProfileCard() {
  const navigate = useNavigate();
  const workerId = useAuthStore((state) => state.user?.id) ?? '';
  const summary = useMySummary();
  const link = profileLink(useCustomerAppUrl(), workerId);
  const [copied, setCopied] = useState(false);

  const share = async () => {
    const text = 'See my verified reviews and book me on Ji-Hajoor';
    try {
      if (navigator.share) {
        await navigator.share({ title: 'My Ji-Hajoor profile', text, url: link });
        return;
      }
      await navigator.clipboard.writeText(link);
      setCopied(true);
    } catch {
      // The person closed the share sheet; nothing to do.
    }
  };

  const average = summary.data?.average ?? null;
  const count = summary.data?.count ?? 0;

  return (
    <Card>
      <CardContent>
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
          <StarRoundedIcon color="warning" />
          <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>
            Your reviews
          </Typography>
        </Stack>
        {count > 0 && average != null ? (
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mt: 1 }}>
            <Typography variant="h5" component="p">
              {average.toFixed(1)}
            </Typography>
            <Rating value={average} precision={0.1} readOnly size="small" />
            <Typography variant="body2" sx={{ color: 'text.secondary' }}>
              {count} review{count === 1 ? '' : 's'}
            </Typography>
          </Stack>
        ) : (
          <Typography variant="body2" sx={{ color: 'text.secondary', mt: 1 }}>
            No reviews yet. Customers are asked to rate every paid job, and sharing your page brings
            more.
          </Typography>
        )}
        <Stack direction="row" spacing={1} sx={{ mt: 2 }}>
          <Button variant="contained" startIcon={<ShareRoundedIcon />} onClick={() => void share()}>
            Share my profile
          </Button>
          <Button variant="outlined" onClick={() => void navigate('/reviews')}>
            See and reply
          </Button>
        </Stack>
        {copied ? (
          <Alert severity="success" sx={{ mt: 1.5 }} onClose={() => setCopied(false)}>
            Link copied: {link}
          </Alert>
        ) : null}
      </CardContent>
    </Card>
  );
}
