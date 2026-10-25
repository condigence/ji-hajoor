import { useRef, useState } from 'react';
import {
  Alert,
  AlertTitle,
  Button,
  Chip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { formatCurrency, formatDateTime } from '@lib/format';
import { generateIdempotencyKey } from '@lib/correlation';
import {
  isRefundableComplaint,
  parseRefundAmount,
  REFUND_OUTCOME_UNKNOWN,
  REFUND_REASON_MAX_LENGTH,
  type AdminComplaint,
  type ComplaintRefund,
  type ComplaintRefundStatus,
} from './api';
import { useApproveComplaintRefund, useComplaintRefund, useResendComplaintRefund } from './hooks';

const REFUND_STATUS_COLORS: Record<ComplaintRefundStatus, 'success' | 'error' | 'warning'> = {
  SUCCEEDED: 'success',
  FAILED: 'error',
  PENDING: 'warning',
};

interface ComplaintRefundDialogProps {
  complaint: AdminComplaint;
  onClose: () => void;
}

/**
 * Refund action for a complaint (Requirement 16.5, 16.6). A complaint carries
 * at most one refund: with none on record the dialog offers the approval form;
 * otherwise it shows the recorded refund, and a PENDING one (outcome unknown,
 * or an attempt that never reported back) can be re-sent. A re-send goes out
 * under the refund's own Payment Service key, so it can never refund twice.
 */
export function ComplaintRefundDialog({ complaint, onClose }: ComplaintRefundDialogProps) {
  const refundQuery = useComplaintRefund(complaint.id);
  const approve = useApproveComplaintRefund(complaint.id);
  const resend = useResendComplaintRefund(complaint.id);

  const [amountInput, setAmountInput] = useState('');
  const [reason, setReason] = useState('');
  const [touched, setTouched] = useState(false);
  // One idempotency key per dialog opening, tied to the amount it was sent
  // with. Retrying the same submission (e.g. after a timeout or an unknown
  // outcome) reuses it, so the Complaint Service replays or safely re-sends the
  // refund instead of refusing it as a second one; changing the amount makes
  // it a different refund, which gets a fresh key.
  const submission = useRef<{ key: string; amount: number | null }>({
    key: generateIdempotencyKey(),
    amount: null,
  });

  const amount = parseRefundAmount(amountInput);
  const amountInvalid = amount === null;
  const reasonInvalid = reason.trim().length > REFUND_REASON_MAX_LENGTH;
  const busy = approve.isPending || resend.isPending;

  const handleApprove = () => {
    setTouched(true);
    if (amount === null || reasonInvalid) return;
    if (submission.current.amount !== null && submission.current.amount !== amount) {
      submission.current.key = generateIdempotencyKey();
    }
    submission.current.amount = amount;
    const trimmedReason = reason.trim();
    approve.mutate({
      amount,
      ...(trimmedReason ? { reason: trimmedReason } : {}),
      idempotencyKey: submission.current.key,
    });
  };

  const refund = refundQuery.data ?? null;
  const showForm = refundQuery.isSuccess && refund === null;

  return (
    <Dialog open onClose={busy ? undefined : onClose} maxWidth="sm" fullWidth>
      <DialogTitle>
        {complaint.bookingReference ? `Refund — ${complaint.bookingReference}` : 'Complaint refund'}
      </DialogTitle>
      <DialogContent dividers>
        {refundQuery.isLoading ? (
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            <CircularProgress size={20} />
            <Typography variant="body2">Checking for an existing refund…</Typography>
          </Stack>
        ) : null}

        {refundQuery.isError ? (
          <Alert
            severity="error"
            action={
              <Button color="inherit" size="small" onClick={() => void refundQuery.refetch()}>
                Retry
              </Button>
            }
          >
            Couldn&apos;t load this complaint&apos;s refund: {refundQuery.error.message}
          </Alert>
        ) : null}

        {refund ? <RefundStatusPanel refund={refund} /> : null}

        {showForm && !isRefundableComplaint(complaint.status) ? (
          <Alert severity="info">
            This complaint is {complaint.status.replace(/_/g, ' ').toLowerCase()}. A refund can only
            be approved while the complaint is open, in progress, escalated or disputed.
          </Alert>
        ) : null}

        {showForm && isRefundableComplaint(complaint.status) ? (
          <Stack spacing={2}>
            <Typography variant="body2" sx={{ color: 'text.secondary' }}>
              A complaint is refunded at most once. The Payment Service caps the amount at what the
              customer paid for the booking.
            </Typography>
            <TextField
              label="Refund amount"
              value={amountInput}
              onChange={(event) => setAmountInput(event.target.value)}
              required
              fullWidth
              error={touched && amountInvalid}
              helperText={
                touched && amountInvalid
                  ? 'Enter a positive amount with at most two decimal places.'
                  : 'In INR, e.g. 450 or 450.50.'
              }
              slotProps={{ htmlInput: { inputMode: 'decimal', autoComplete: 'off' } }}
            />
            <TextField
              label="Reason"
              value={reason}
              onChange={(event) => setReason(event.target.value)}
              multiline
              minRows={2}
              fullWidth
              error={touched && reasonInvalid}
              helperText={
                touched && reasonInvalid
                  ? `At most ${REFUND_REASON_MAX_LENGTH} characters.`
                  : 'Optional. Recorded with the refund for audit.'
              }
            />
          </Stack>
        ) : null}

        <MutationError
          error={approve.error}
          // An unknown outcome leaves a PENDING refund, shown above once re-read.
          hiddenWhenUnknown={refund !== null}
          action="approval"
        />
        <MutationError error={resend.error} hiddenWhenUnknown={false} action="re-send" />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={busy}>
          Close
        </Button>
        {refund?.status === 'PENDING' ? (
          <>
            <Button onClick={() => void refundQuery.refetch()} disabled={busy}>
              Refresh
            </Button>
            <Button
              variant="contained"
              onClick={() => resend.mutate()}
              disabled={busy || !refund.resendable}
            >
              {resend.isPending ? 'Re-sending…' : 'Re-send'}
            </Button>
          </>
        ) : null}
        {showForm && isRefundableComplaint(complaint.status) ? (
          <Button variant="contained" onClick={handleApprove} disabled={busy}>
            {approve.isPending ? 'Submitting…' : 'Approve refund'}
          </Button>
        ) : null}
      </DialogActions>
    </Dialog>
  );
}

/** The recorded refund and what its status means for the agent. */
function RefundStatusPanel({ refund }: { refund: ComplaintRefund }) {
  return (
    <Stack spacing={2}>
      <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
        <Typography variant="subtitle1">{formatCurrency(refund.amount)}</Typography>
        <Chip
          size="small"
          color={REFUND_STATUS_COLORS[refund.status]}
          label={refund.status.toLowerCase()}
          sx={{ textTransform: 'capitalize' }}
        />
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          approved {formatDateTime(refund.requestedAt)}
        </Typography>
      </Stack>
      {refund.reason ? <Typography variant="body2">Reason: {refund.reason}</Typography> : null}
      <RefundOutcome refund={refund} />
    </Stack>
  );
}

function RefundOutcome({ refund }: { refund: ComplaintRefund }) {
  switch (refund.status) {
    case 'SUCCEEDED':
      return (
        <Alert severity="success">
          <AlertTitle>Refund succeeded</AlertTitle>
          {refund.externalReference ? `Payment reference ${refund.externalReference}. ` : null}
          {refund.completedAt ? `Completed ${formatDateTime(refund.completedAt)}.` : null}
        </Alert>
      );
    case 'FAILED':
      return (
        <Alert severity="error">
          <AlertTitle>Refund failed</AlertTitle>
          The Payment Service rejected it
          {refund.failureReason ? `: ${refund.failureReason}` : ''}. The customer has been told and
          Finance has been alerted to process it manually; it can&apos;t be retried here.
        </Alert>
      );
    case 'PENDING':
      return refund.outcomeUnknown ? (
        <Alert severity="warning">
          <AlertTitle>Pending: outcome unknown, finance alerted</AlertTitle>
          The Payment Service didn&apos;t give a definitive answer
          {refund.failureReason ? ` (${refund.failureReason})` : ''}, so the money may or may not
          have moved. Re-send it once the Payment Service is reachable: it goes out under the same
          payment key, so it can&apos;t refund twice.
        </Alert>
      ) : (
        <Alert severity="info">
          <AlertTitle>Pending: awaiting the Payment Service</AlertTitle>
          {refund.resendable
            ? 'The last attempt never reported back. It can be re-sent safely under the same payment key.'
            : `An attempt is in flight${
                refund.lastAttemptAt ? ` (started ${formatDateTime(refund.lastAttemptAt)})` : ''
              }. This view refreshes while it is pending.`}
        </Alert>
      );
  }
}

interface MutationErrorProps {
  error: { code: string; message: string } | null;
  hiddenWhenUnknown: boolean;
  action: string;
}

function MutationError({ error, hiddenWhenUnknown, action }: MutationErrorProps) {
  if (!error) return null;
  if (error.code === REFUND_OUTCOME_UNKNOWN) {
    if (hiddenWhenUnknown) return null;
    return (
      <Alert severity="warning" sx={{ mt: 2 }}>
        The {action} reached the Payment Service but its outcome is unknown. Finance has been
        alerted and the refund stays pending.
      </Alert>
    );
  }
  return (
    <Alert severity="error" sx={{ mt: 2 }}>
      {error.message}
    </Alert>
  );
}
