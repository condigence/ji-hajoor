import { useState } from 'react';
import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  InputAdornment,
  TextField,
  Typography,
} from '@mui/material';
import { useSetAgencyShare } from './hooks';
import { tenantErrorMessage, type Tenant } from './model';

/** The highest share an agency can take; a worker always keeps at least half their net. */
export const MAX_AGENCY_SHARE = 50;

interface AgencyShareDialogProps {
  tenant: Tenant;
  onClose: () => void;
}

/**
 * Sets the agency's share of its workers' net earning (gross less the platform
 * fee). It applies to jobs credited from now on; earlier jobs keep their split.
 */
export function AgencyShareDialog({ tenant, onClose }: AgencyShareDialogProps) {
  const save = useSetAgencyShare();
  const [value, setValue] = useState(String(tenant.agencySharePercent ?? 0));
  const percent = Number(value);
  const valid =
    value.trim() !== '' && Number.isFinite(percent) && percent >= 0 && percent <= MAX_AGENCY_SHARE;

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle>Agency share — {tenant.name}</DialogTitle>
      <DialogContent>
        <Typography variant="body2" sx={{ color: 'text.secondary', mb: 2 }}>
          The part of each worker&apos;s net earning (after the platform fee) credited to the agency
          on jobs its workers complete. It applies to future jobs only.
        </Typography>
        <TextField
          fullWidth
          label="Share"
          type="number"
          value={value}
          onChange={(event) => setValue(event.target.value)}
          error={!valid}
          helperText={valid ? ' ' : `Enter a number from 0 to ${MAX_AGENCY_SHARE}.`}
          slotProps={{
            htmlInput: { min: 0, max: MAX_AGENCY_SHARE, step: 0.5 },
            input: { endAdornment: <InputAdornment position="end">%</InputAdornment> },
          }}
        />
        {save.error ? <Alert severity="error">{tenantErrorMessage(save.error)}</Alert> : null}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} color="inherit">
          Cancel
        </Button>
        <Button
          variant="contained"
          disabled={!valid || save.isPending}
          onClick={() => save.mutate({ id: tenant.id, percent }, { onSuccess: onClose })}
        >
          Save
        </Button>
      </DialogActions>
    </Dialog>
  );
}
