import { apiClient, isApiError } from '@api/client';
import { SUPPORT_ROLES } from '@config/roles';
import type { UserRole } from '@stores/authStore';

/**
 * Complaint Management bindings (Requirement 19.2, Requirement 13).
 *
 * Support agents and admins triage complaints, add resolution notes, and
 * transition them through the lifecycle. Actions are recorded in the Audit_Log.
 *
 * Endpoints (see design.md — Admin Service / Complaint Service):
 * - GET   /admin/complaints              — list/search complaints (status filter)
 * - PATCH /admin/complaints/{id}         — update status / add resolution note
 * - POST  /complaints/{id}/refund        — approve the complaint's (single) refund
 * - GET   /complaints/{id}/refund        — the refund as recorded (404 when none)
 * - POST  /complaints/{id}/refund/resend — re-send a PENDING refund whose outcome is unknown
 */

/**
 * The Complaint Service lifecycle (Requirement 16). ESCALATED (SLA breach,
 * 16.4) and REFUND_FAILED (payment rejected the refund, 16.6) are set by the
 * system, never chosen by an agent; RESOLVED and CLOSED are terminal.
 */
export type ComplaintStatus =
  'OPEN' | 'IN_PROGRESS' | 'ESCALATED' | 'DISPUTED' | 'REFUND_FAILED' | 'RESOLVED' | 'CLOSED';

/** Every status, in lifecycle order, for the filter. */
export const COMPLAINT_STATUSES: ReadonlyArray<ComplaintStatus> = [
  'OPEN',
  'IN_PROGRESS',
  'ESCALATED',
  'DISPUTED',
  'REFUND_FAILED',
  'RESOLVED',
  'CLOSED',
];

/** Terminal statuses: the Complaint Service refuses any change out of them. */
export function isTerminalComplaint(status: ComplaintStatus): boolean {
  return status === 'RESOLVED' || status === 'CLOSED';
}

/**
 * The statuses an agent may move a complaint to from its current one, mirroring
 * ComplaintService.changeStatus:
 * - nothing leaves RESOLVED or CLOSED;
 * - ESCALATED and REFUND_FAILED are system-set, so they are never offered;
 * - DISPUTED holds the provider's settlement until the complaint finishes, so
 *   it only moves on to RESOLVED or CLOSED (which release the hold);
 * - OPEN is where a complaint starts, not somewhere to send it back to.
 */
export function complaintTransitions(current: ComplaintStatus): ComplaintStatus[] {
  switch (current) {
    case 'RESOLVED':
    case 'CLOSED':
      return [];
    case 'DISPUTED':
      return ['RESOLVED', 'CLOSED'];
    case 'IN_PROGRESS':
      return ['DISPUTED', 'RESOLVED', 'CLOSED'];
    case 'OPEN':
    case 'ESCALATED':
    case 'REFUND_FAILED':
      return ['IN_PROGRESS', 'DISPUTED', 'RESOLVED', 'CLOSED'];
  }
}

/**
 * A complaint row. The Complaint Service owns the complaint; the booking
 * reference and the complainant's name live in other services and may come
 * back null or absent.
 */
export interface AdminComplaint {
  id: string;
  bookingReference?: string | null;
  raisedByName?: string | null;
  category: string;
  summary: string;
  status: ComplaintStatus;
  /** ISO 8601 SLA deadline; used to flag breaches in the UI. */
  slaDueAt: string;
  createdAt: string;
  /** The latest resolution note staff recorded; null when there is none. */
  resolutionNote?: string | null;
}

export interface ComplaintUpdatePayload {
  status: ComplaintStatus;
  resolutionNote?: string;
}

/** GET /admin/complaints — complaints, optionally filtered by search + status. */
export async function fetchComplaints(params: {
  search?: string;
  status?: ComplaintStatus | '';
}): Promise<AdminComplaint[]> {
  const query: Record<string, string> = {};
  if (params.search) query.search = params.search;
  if (params.status) query.status = params.status;
  const { data } = await apiClient.get<AdminComplaint[]>('/admin/complaints', {
    params: Object.keys(query).length > 0 ? query : undefined,
  });
  return data;
}

/** PATCH /admin/complaints/{id} — update status / add a resolution note. */
export async function updateComplaint(
  id: string,
  payload: ComplaintUpdatePayload,
): Promise<AdminComplaint> {
  const { data } = await apiClient.patch<AdminComplaint>(`/admin/complaints/${id}`, payload);
  return data;
}

/** True when the complaint is still open and past its SLA deadline. */
export function isSlaBreached(complaint: AdminComplaint): boolean {
  if (isTerminalComplaint(complaint.status)) return false;
  const due = new Date(complaint.slaDueAt).getTime();
  return Number.isFinite(due) && due < Date.now();
}

// ---- Complaint refund (Requirement 16.5, 16.6) ------------------------------

/**
 * Who may approve, read and re-send a complaint refund. Mirrors the Complaint
 * Service's ComplaintRbacConfig SUPPORT_TIER rule for POST/GET
 * /complaints/{id}/refund and POST /complaints/{id}/refund/resend.
 */
export const COMPLAINT_REFUND_ROLES: readonly UserRole[] = SUPPORT_ROLES;

/**
 * The refund's lifecycle in the Complaint Service. PENDING is written before
 * the Payment Service is called and stays while its answer is unknown.
 */
export type ComplaintRefundStatus = 'PENDING' | 'SUCCEEDED' | 'FAILED';

/** RefundResponse from the Complaint Service. */
export interface ComplaintRefund {
  id: string;
  complaintId: string;
  bookingId: string;
  amount: number;
  reason?: string | null;
  approvedBy: string;
  status: ComplaintRefundStatus;
  /** Payment Service refund reference once SUCCEEDED. */
  externalReference?: string | null;
  /** Why it FAILED, or why the latest attempt's outcome is unknown (PENDING). */
  failureReason?: string | null;
  requestedAt: string;
  completedAt?: string | null;
  lastAttemptAt?: string | null;
  /** PENDING and the latest attempt ended without an answer; finance was alerted. */
  outcomeUnknown: boolean;
  /** PENDING and POST .../refund/resend would re-send it now. */
  resendable: boolean;
}

/** RefundRequest: amount > 0 with at most 2 decimals; reason ≤ 500 chars; key ≤ 64 chars. */
export interface ComplaintRefundPayload {
  amount: number;
  reason?: string;
  /**
   * Generated once per dialog opening and reused when the same submission is
   * retried, so a retry replays (or safely re-sends) the refund instead of
   * being refused as a second one.
   */
  idempotencyKey: string;
}

/** Error code of a refund submitted to the Payment Service with no definitive answer. */
export const REFUND_OUTCOME_UNKNOWN = 'REFUND_OUTCOME_UNKNOWN';

/** Matches the RefundRequest constraints: @Positive @Digits(integer = 10, fraction = 2). */
const REFUND_AMOUNT_PATTERN = /^\d{1,10}(\.\d{1,2})?$/;

export const REFUND_REASON_MAX_LENGTH = 500;

/**
 * Parses a refund amount typed into the dialog. Returns the amount, or null
 * when it is not a positive number with at most 10 integer digits and 2
 * decimals (what the Complaint Service accepts).
 */
export function parseRefundAmount(input: string): number | null {
  const value = input.trim();
  if (!REFUND_AMOUNT_PATTERN.test(value)) return null;
  const amount = Number(value);
  return Number.isFinite(amount) && amount > 0 ? amount : null;
}

/** POST /complaints/{id}/refund — approve the complaint's refund (202 with the recorded refund). */
export async function approveComplaintRefund(
  complaintId: string,
  payload: ComplaintRefundPayload,
): Promise<ComplaintRefund> {
  const { data } = await apiClient.post<ComplaintRefund>(
    `/complaints/${complaintId}/refund`,
    payload,
  );
  return data;
}

/** GET /complaints/{id}/refund — the recorded refund, or null when none was approved. */
export async function fetchComplaintRefund(complaintId: string): Promise<ComplaintRefund | null> {
  try {
    const { data } = await apiClient.get<ComplaintRefund>(`/complaints/${complaintId}/refund`);
    return data;
  } catch (error) {
    if (isApiError(error) && error.status === 404 && error.code === 'REFUND_NOT_FOUND') {
      return null;
    }
    throw error;
  }
}

/**
 * POST /complaints/{id}/refund/resend — re-send a PENDING refund under its
 * original Payment Service idempotency key, so it can never refund twice.
 */
export async function resendComplaintRefund(complaintId: string): Promise<ComplaintRefund> {
  const { data } = await apiClient.post<ComplaintRefund>(
    `/complaints/${complaintId}/refund/resend`,
  );
  return data;
}

/**
 * Statuses from which the Complaint Service accepts a new refund approval
 * (ComplaintService.REFUNDABLE_STATES). An existing refund can still be viewed
 * and re-sent from any status.
 */
export function isRefundableComplaint(status: ComplaintStatus): boolean {
  return (
    status === 'OPEN' || status === 'IN_PROGRESS' || status === 'ESCALATED' || status === 'DISPUTED'
  );
}
