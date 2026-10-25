import { useState } from 'react';
import {
  Alert,
  Button,
  Card,
  CardContent,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  Stack,
  Typography,
} from '@mui/material';
import GroupsRoundedIcon from '@mui/icons-material/GroupsRounded';
import { agencyErrorMessage, useAgencyAction, useMyAgency } from './hooks';

interface AgencyCardProps {
  /** On the dashboard the card appears only while an invitation waits for an answer. */
  invitationsOnly?: boolean;
}

/**
 * The worker's agency: independent, or part of an agency's team. Agencies
 * invite workers; nobody joins one without accepting here, and a worker can
 * leave to work independently again. Each invitation shows the agency's share
 * of the worker's net earning, so the worker knows the terms before joining.
 */
export function AgencyCard({ invitationsOnly = false }: AgencyCardProps) {
  const query = useMyAgency();
  const action = useAgencyAction();
  const [confirmLeave, setConfirmLeave] = useState(false);

  const state = query.data;
  if (!state) return null;
  const { agency, invitations } = state;
  if (invitationsOnly && invitations.length === 0) return null;

  return (
    <Card>
      <CardContent>
        <Stack spacing={1.5}>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            <GroupsRoundedIcon color="primary" />
            <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>
              {invitations.length > 0 && invitationsOnly ? 'Agency invitation' : 'Agency'}
            </Typography>
          </Stack>

          {!invitationsOnly ? (
            agency ? (
              <Stack spacing={1}>
                <Typography variant="body2">
                  You work with <strong>{agency.name}</strong>. They can assign you jobs
                  {agency.agencySharePercent > 0
                    ? `, and receive ${agency.agencySharePercent}% of your net earning on each job.`
                    : '.'}
                </Typography>
                <Button
                  size="small"
                  color="inherit"
                  variant="outlined"
                  sx={{ alignSelf: 'flex-start' }}
                  onClick={() => setConfirmLeave(true)}
                >
                  Leave agency
                </Button>
              </Stack>
            ) : (
              <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                You work independently. Agencies can invite you to their team; invitations appear
                here.
              </Typography>
            )
          ) : null}

          {invitations.map((invitation) => (
            <Stack
              key={invitation.id}
              spacing={1}
              sx={{ p: 1.5, borderRadius: 2, bgcolor: 'action.hover' }}
            >
              <Typography variant="body2">
                <strong>{invitation.agencyName ?? 'An agency'}</strong> invited you to join their
                team.
                {invitation.agencySharePercent
                  ? ` They would receive ${invitation.agencySharePercent}% of your net earning on each job.`
                  : ' You would keep your whole net earning.'}
              </Typography>
              <Stack direction="row" spacing={1}>
                <Button
                  size="small"
                  variant="contained"
                  disabled={action.isPending}
                  onClick={() => action.mutate({ kind: 'accept', id: invitation.id })}
                >
                  Accept
                </Button>
                <Button
                  size="small"
                  color="inherit"
                  disabled={action.isPending}
                  onClick={() => action.mutate({ kind: 'decline', id: invitation.id })}
                >
                  Decline
                </Button>
              </Stack>
            </Stack>
          ))}

          {action.error ? <Alert severity="error">{agencyErrorMessage(action.error)}</Alert> : null}
        </Stack>
      </CardContent>

      <Dialog open={confirmLeave} onClose={() => setConfirmLeave(false)}>
        <DialogTitle>Leave {agency?.name ?? 'the agency'}?</DialogTitle>
        <DialogContent>
          <DialogContentText>
            You will work independently. Jobs already assigned to you stay as they are.
          </DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button color="inherit" onClick={() => setConfirmLeave(false)}>
            Stay
          </Button>
          <Button
            color="error"
            disabled={action.isPending}
            onClick={() =>
              action.mutate({ kind: 'leave' }, { onSuccess: () => setConfirmLeave(false) })
            }
          >
            Leave
          </Button>
        </DialogActions>
      </Dialog>
    </Card>
  );
}
