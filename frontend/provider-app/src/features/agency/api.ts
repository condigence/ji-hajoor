import { apiClient } from '@api/client';

/**
 * A worker's agency membership (provider-service `/providers/me/agency`).
 * Independent workers belong to no agency; an agency can invite a worker, who
 * joins only by accepting here, and a worker can leave to work independently.
 */

export interface AgencyRef {
  tenantId: string;
  name: string;
  /** The agency's share of the worker's net earning on each job, in percent. */
  agencySharePercent: number;
}

export interface AgencyInvitation {
  id: string;
  tenantId: string;
  agencyName?: string | null;
  status: 'PENDING' | 'ACCEPTED' | 'DECLINED' | 'CANCELLED';
  createdAt: string;
  agencySharePercent?: number | null;
}

export interface WorkerAgency {
  /** Null while the worker is independent. */
  agency: AgencyRef | null;
  invitations: AgencyInvitation[];
}

export async function fetchMyAgency(): Promise<WorkerAgency> {
  const { data } = await apiClient.get<WorkerAgency>('/providers/me/agency');
  return data;
}

export async function acceptInvitation(id: string): Promise<WorkerAgency> {
  const { data } = await apiClient.post<WorkerAgency>(
    `/providers/me/agency/invitations/${id}/accept`,
  );
  return data;
}

export async function declineInvitation(id: string): Promise<WorkerAgency> {
  const { data } = await apiClient.post<WorkerAgency>(
    `/providers/me/agency/invitations/${id}/decline`,
  );
  return data;
}

export async function leaveAgency(): Promise<WorkerAgency> {
  const { data } = await apiClient.post<WorkerAgency>('/providers/me/agency/leave');
  return data;
}
