import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import { useAuthStore } from '@stores/authStore';
import {
  CUSTOMER_APP_URL_KEY,
  fetchClientConfig,
  fetchMySummary,
  fetchReceivedReviews,
  replyToReview,
  type RatingSummary,
  type ReceivedReview,
} from './api';

/** Where customers book; the share link points there. Falls back to the live site. */
const DEFAULT_CUSTOMER_APP_URL = 'https://jihajoor.com';

export const reviewKeys = {
  received: ['reviews', 'received'] as const,
  summary: (id: string) => ['reviews', 'summary', id] as const,
  config: ['config', 'client'] as const,
};

export function useReceivedReviews() {
  return useQuery<ReceivedReview[], ApiError>({
    queryKey: reviewKeys.received,
    queryFn: fetchReceivedReviews,
  });
}

export function useMySummary() {
  const workerId = useAuthStore((state) => state.user?.id) ?? '';
  return useQuery<RatingSummary, ApiError>({
    queryKey: reviewKeys.summary(workerId),
    queryFn: () => fetchMySummary(workerId),
    enabled: Boolean(workerId),
  });
}

export function useReply() {
  const client = useQueryClient();
  return useMutation<ReceivedReview, ApiError, { reviewId: string; text: string }>({
    mutationFn: ({ reviewId, text }) => replyToReview(reviewId, text),
    onSuccess: (updated) => {
      client.setQueryData<ReceivedReview[]>(reviewKeys.received, (current) =>
        current?.map((review) => (review.id === updated.id ? updated : review)),
      );
    },
  });
}

export function useCustomerAppUrl(): string {
  const { data } = useQuery({
    queryKey: reviewKeys.config,
    queryFn: fetchClientConfig,
    staleTime: 5 * 60_000,
    retry: 1,
  });
  return data?.[CUSTOMER_APP_URL_KEY] ?? DEFAULT_CUSTOMER_APP_URL;
}
