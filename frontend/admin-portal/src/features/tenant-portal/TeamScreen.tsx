import { useState } from 'react';
import {
  Alert,
  Button,
  Card,
  CardContent,
  Collapse,
  List,
  ListItem,
  ListItemText,
  Stack,
  Typography,
} from '@mui/material';
import PersonAddAlt1RoundedIcon from '@mui/icons-material/PersonAddAlt1Rounded';
import { ModuleScreen } from '@components/ModuleScreen';
import { QueryStateView } from '@components/QueryStateView';
import { DataTable } from '@components/DataTable';
import { AddByMobileForm } from '@features/tenants/AddByMobileForm';
import { ConfirmRemoveDialog } from '@features/tenants/ConfirmRemoveDialog';
import type { TeamProvider } from '@features/tenants/model';
import { teamProviderColumns } from '@features/tenants/teamColumns';
import { tenantErrorMessage } from '@features/tenants/model';
import { formatDateTime } from '@lib/format';
import {
  useCancelInvitation,
  useInvitations,
  useInviteWorker,
  useRemoveTeamProvider,
  useTeam,
} from './hooks';

/**
 * Team (Requirements MT-3, MT-11.4): the agency's workers with their primary
 * skill, verification status, rating and whether they are available now. The
 * admin invites a worker by mobile number; the worker joins only after accepting
 * in the Ji-Hajoor partner app, so unanswered invitations are listed and can be
 * withdrawn. Removing someone leaves jobs already assigned to them alone
 * (Requirement MT-3.3), which the confirmation says.
 */
export function TeamScreen() {
  const teamQuery = useTeam();
  const add = useInviteWorker();
  const invitationsQuery = useInvitations();
  const cancel = useCancelInvitation();
  const [invitedName, setInvitedName] = useState<string | null>(null);
  const pending = invitationsQuery.data ?? [];
  const remove = useRemoveTeamProvider();
  const [adding, setAdding] = useState(false);
  const [removing, setRemoving] = useState<TeamProvider | null>(null);

  const team = teamQuery.data ?? [];
  const columns = teamProviderColumns((provider) => {
    remove.reset();
    setRemoving(provider);
  });

  return (
    <ModuleScreen
      title="Team"
      description="Your agency's workers. Workers join when they accept your invitation, and only workers verified by Ji-Hajoor can be assigned to requests."
      actions={
        <Button
          variant="contained"
          startIcon={<PersonAddAlt1RoundedIcon />}
          onClick={() => {
            add.reset();
            setAdding((open) => !open);
          }}
        >
          Invite worker
        </Button>
      }
    >
      <Collapse in={adding} unmountOnExit>
        <Card sx={{ mb: 2 }}>
          <CardContent>
            <Stack spacing={1.5}>
              <Typography variant="subtitle1">Invite a worker by mobile number</Typography>
              <AddByMobileForm
                label="Worker's mobile number"
                submitLabel="Send invitation"
                helperText="They must have signed up in the Ji-Hajoor partner app. They join your team when they accept."
                onAdd={(mobile) =>
                  add.mutateAsync(mobile).then((invitation) => {
                    setInvitedName(
                      invitation.workerName ?? invitation.mobileNumber ?? 'The worker',
                    );
                    setAdding(false);
                  })
                }
                isPending={add.isPending}
                error={add.error}
              />
            </Stack>
          </CardContent>
        </Card>
      </Collapse>

      {invitedName ? (
        <Alert severity="success" onClose={() => setInvitedName(null)} sx={{ mb: 2 }}>
          Invitation sent. {invitedName} joins your team once they accept it in the partner app.
        </Alert>
      ) : null}

      {pending.length > 0 ? (
        <Card sx={{ mb: 2 }}>
          <CardContent>
            <Typography variant="subtitle1">Waiting for an answer</Typography>
            {cancel.error ? (
              <Alert severity="error" sx={{ mt: 1 }}>
                {tenantErrorMessage(cancel.error)}
              </Alert>
            ) : null}
            <List dense disablePadding>
              {pending.map((invitation) => (
                <ListItem
                  key={invitation.id}
                  disableGutters
                  secondaryAction={
                    <Button
                      size="small"
                      color="inherit"
                      disabled={cancel.isPending}
                      onClick={() => cancel.mutate(invitation.id)}
                    >
                      Withdraw
                    </Button>
                  }
                >
                  <ListItemText
                    primary={invitation.workerName ?? 'Worker'}
                    secondary={`Invited ${formatDateTime(invitation.createdAt)}`}
                  />
                </ListItem>
              ))}
            </List>
          </CardContent>
        </Card>
      ) : null}

      <QueryStateView
        isLoading={teamQuery.isLoading}
        isError={teamQuery.isError}
        error={teamQuery.error}
        onRetry={() => void teamQuery.refetch()}
        isEmpty={team.length === 0}
        emptyMessage="No workers in your team yet. Invite one by their mobile number."
      >
        <DataTable columns={columns} rows={team} rowKey={(row) => row.providerId} />
      </QueryStateView>

      {removing ? (
        <ConfirmRemoveDialog
          title="Remove from team?"
          message={`${removing.displayName ?? 'This worker'} will no longer be assignable by your agency. Jobs already assigned to them are unaffected.`}
          confirmLabel="Remove"
          isPending={remove.isPending}
          error={remove.error}
          onConfirm={() =>
            remove.mutate(removing.providerId, { onSuccess: () => setRemoving(null) })
          }
          onClose={() => setRemoving(null)}
        />
      ) : null}
    </ModuleScreen>
  );
}
