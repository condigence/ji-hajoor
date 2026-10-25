import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import {
  acceptInvitation,
  declineInvitation,
  fetchMyAgency,
  leaveAgency,
  type WorkerAgency,
} from './api';

export const agencyKeys = { mine: ['agency', 'mine'] as const };

/** The worker's agency and the invitations waiting for an answer. */
export function useMyAgency(): UseQueryResult<WorkerAgency, ApiError> {
  return useQuery<WorkerAgency, ApiError>({
    queryKey: agencyKeys.mine,
    queryFn: fetchMyAgency,
    staleTime: 60_000,
  });
}

type Action = { kind: 'accept' | 'decline'; id: string } | { kind: 'leave' };

/** Accept or decline an invitation, or leave the agency; the answer is the new state. */
export function useAgencyAction(): UseMutationResult<WorkerAgency, ApiError, Action> {
  const queryClient = useQueryClient();
  return useMutation<WorkerAgency, ApiError, Action>({
    mutationFn: (action) => {
      if (action.kind === 'accept') return acceptInvitation(action.id);
      if (action.kind === 'decline') return declineInvitation(action.id);
      return leaveAgency();
    },
    onSuccess: (state) => {
      queryClient.setQueryData(agencyKeys.mine, state);
    },
  });
}

const MESSAGES: Record<string, string> = {
  INVITATION_NOT_FOUND: 'That invitation no longer exists.',
  INVITATION_NOT_PENDING: 'That invitation was already answered or withdrawn.',
  TENANT_NOT_ACTIVE: 'That agency is not active on Ji-Hajoor right now.',
  PROVIDER_IN_OTHER_TENANT: 'Leave your current agency before joining another.',
  NOT_IN_AGENCY: 'You are not part of an agency.',
};

export function agencyErrorMessage(error: ApiError): string {
  return MESSAGES[error.code] ?? error.message;
}
