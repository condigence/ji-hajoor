import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import {
  approveComplaintRefund,
  fetchComplaintRefund,
  fetchComplaints,
  resendComplaintRefund,
  updateComplaint,
  type AdminComplaint,
  type ComplaintRefund,
  type ComplaintRefundPayload,
  type ComplaintStatus,
  type ComplaintUpdatePayload,
} from './api';

export const complaintKeys = {
  list: (search: string, status: ComplaintStatus | '') =>
    ['admin', 'complaints', { search, status }] as const,
  refund: (complaintId: string) => ['admin', 'complaints', 'refund', complaintId] as const,
};

/** How often an open refund dialog re-reads a PENDING refund. */
const PENDING_REFUND_POLL_MS = 10_000;

/** Complaints filtered by an optional search term and status (Requirement 19.2). */
export function useComplaints(
  search: string,
  status: ComplaintStatus | '',
): UseQueryResult<AdminComplaint[], ApiError> {
  return useQuery<AdminComplaint[], ApiError>({
    queryKey: complaintKeys.list(search, status),
    queryFn: () => fetchComplaints({ ...(search ? { search } : {}), status }),
  });
}

/** Update a complaint's status / resolution note. */
export function useUpdateComplaint(): UseMutationResult<
  AdminComplaint,
  ApiError,
  { id: string; payload: ComplaintUpdatePayload }
> {
  const queryClient = useQueryClient();
  return useMutation<AdminComplaint, ApiError, { id: string; payload: ComplaintUpdatePayload }>({
    mutationFn: ({ id, payload }) => updateComplaint(id, payload),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['admin', 'complaints'] });
    },
  });
}

/**
 * The complaint's refund, or null when none was approved. Polled while it is
 * PENDING so the dialog shows the outcome once the Payment Service answers.
 */
export function useComplaintRefund(
  complaintId: string,
): UseQueryResult<ComplaintRefund | null, ApiError> {
  return useQuery<ComplaintRefund | null, ApiError>({
    queryKey: complaintKeys.refund(complaintId),
    queryFn: () => fetchComplaintRefund(complaintId),
    staleTime: 0,
    refetchInterval: (query) =>
      query.state.data?.status === 'PENDING' ? PENDING_REFUND_POLL_MS : false,
  });
}

/**
 * After a refund call: store the refund it returned, then refresh the list and
 * the refund itself (the refund key sits under ['admin', 'complaints']). That
 * re-read is what shows a refund whose outcome came back unknown (it exists,
 * PENDING), and the list picks up a rejection's move to REFUND_FAILED.
 */
function useRefundCallbacks() {
  const queryClient = useQueryClient();
  return {
    onSuccess: (refund: ComplaintRefund) => {
      queryClient.setQueryData(complaintKeys.refund(refund.complaintId), refund);
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: ['admin', 'complaints'] });
    },
  };
}

/** Approve a complaint refund (POST /complaints/{id}/refund). */
export function useApproveComplaintRefund(
  complaintId: string,
): UseMutationResult<ComplaintRefund, ApiError, ComplaintRefundPayload> {
  const callbacks = useRefundCallbacks();
  return useMutation<ComplaintRefund, ApiError, ComplaintRefundPayload>({
    mutationFn: (payload) => approveComplaintRefund(complaintId, payload),
    ...callbacks,
  });
}

/** Re-send a PENDING complaint refund (POST /complaints/{id}/refund/resend). */
export function useResendComplaintRefund(
  complaintId: string,
): UseMutationResult<ComplaintRefund, ApiError, void> {
  const callbacks = useRefundCallbacks();
  return useMutation<ComplaintRefund, ApiError, void>({
    mutationFn: () => resendComplaintRefund(complaintId),
    ...callbacks,
  });
}
