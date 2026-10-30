import { apiClient } from '@api/client';

/**
 * Reviews (rating-review-service and the public worker page).
 *
 * - GET  /reviews/prompts/mine                — the caller's recent review prompts
 * - POST /reviews                              — rate a completed, paid job (once)
 * - GET  /public/workers/{id}                 — a verified worker's public profile (no sign-in)
 * - GET  /public/workers/{id}/reviews?page=   — their verified reviews and rating summary
 */

export type PromptStatus = 'OPEN' | 'REVIEWED' | 'EXPIRED';

export interface ReviewPrompt {
  bookingId: string;
  role: 'CUSTOMER' | 'PROVIDER';
  revieweeId: string;
  status: PromptStatus;
  expiresAt: string;
  /** Your overall rating, once you reviewed. */
  myRating?: number | null;
}

export interface ReviewSubmission {
  bookingId: string;
  overall: number;
  quality: number;
  timeliness: number;
  behavior: number;
  pricingTransparency: number;
  reviewText?: string;
}

export interface PublicReview {
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
  /** Keyed "5".."1". */
  distribution: Record<string, number>;
  quality?: number | null;
  timeliness?: number | null;
  behavior?: number | null;
  pricingTransparency?: number | null;
}

export interface PublicReviews {
  summary: RatingSummary;
  reviews: PublicReview[];
  page: number;
  size: number;
  hasMore: boolean;
}

export interface PublicWorker {
  id: string;
  displayName: string;
  skills: string[];
  yearsExperience: number;
  rating?: number | null;
  ratingCount: number;
  agencyName?: string | null;
  /** "YYYY-MM". */
  memberSince?: string | null;
  verified: boolean;
}

export async function fetchMyPrompts(): Promise<ReviewPrompt[]> {
  const { data } = await apiClient.get<ReviewPrompt[]>('/reviews/prompts/mine');
  return data;
}

export async function submitReview(review: ReviewSubmission): Promise<void> {
  await apiClient.post('/reviews', review);
}

export async function fetchPublicWorker(workerId: string): Promise<PublicWorker> {
  const { data } = await apiClient.get<PublicWorker>(
    `/public/workers/${encodeURIComponent(workerId)}`,
  );
  return data;
}

export async function fetchPublicReviews(workerId: string, page: number): Promise<PublicReviews> {
  const { data } = await apiClient.get<PublicReviews>(
    `/public/workers/${encodeURIComponent(workerId)}/reviews`,
    { params: { page, size: 10 } },
  );
  return data;
}
