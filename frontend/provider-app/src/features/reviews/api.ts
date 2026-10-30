import { apiClient } from '@api/client';

/**
 * A worker's reviews (rating-review-service).
 *
 * - GET  /reviews/received                 — reviews customers left you, with your replies
 * - POST /reviews/{id}/reply {text}        — your public reply (1-500 characters; replaces an earlier one)
 * - GET  /public/workers/{id}/reviews      — your public rating summary, as customers see it
 * - GET  /config/client                    — the customer site's address, for your share link
 */

export interface ReceivedReview {
  id: string;
  overall: number;
  quality?: number | null;
  timeliness?: number | null;
  behavior?: number | null;
  pricingTransparency?: number | null;
  text?: string | null;
  submittedAt: string;
  reply?: string | null;
  repliedAt?: string | null;
}

export interface RatingSummary {
  average?: number | null;
  count: number;
  distribution: Record<string, number>;
}

export const MAX_REPLY_LENGTH = 500;
export const CUSTOMER_APP_URL_KEY = 'jihajoor.apps.customer-app-url';

export async function fetchReceivedReviews(): Promise<ReceivedReview[]> {
  const { data } = await apiClient.get<ReceivedReview[]>('/reviews/received');
  return data;
}

export async function replyToReview(reviewId: string, text: string): Promise<ReceivedReview> {
  const { data } = await apiClient.post<ReceivedReview>(`/reviews/${reviewId}/reply`, { text });
  return data;
}

export async function fetchMySummary(workerId: string): Promise<RatingSummary> {
  const { data } = await apiClient.get<{ summary: RatingSummary }>(
    `/public/workers/${encodeURIComponent(workerId)}/reviews`,
    { params: { page: 0, size: 1 } },
  );
  return data.summary;
}

export async function fetchClientConfig(): Promise<Record<string, string>> {
  const { data } = await apiClient.get<Record<string, string>>('/config/client');
  return data;
}

/** The public page customers see for this worker. */
export function profileLink(customerAppUrl: string, workerId: string): string {
  return `${customerAppUrl.replace(/\/+$/, '')}/workers/${workerId}`;
}
