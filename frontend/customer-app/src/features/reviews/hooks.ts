import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import { useAuthStore } from '@stores/authStore';
import {
  fetchMyPrompts,
  fetchPublicReviews,
  fetchPublicWorker,
  submitReview,
  type PublicReviews,
  type PublicWorker,
  type ReviewPrompt,
  type ReviewSubmission,
} from './api';

export const reviewKeys = {
  prompts: ['reviews', 'prompts'] as const,
  worker: (id: string) => ['public', 'worker', id] as const,
  workerReviews: (id: string) => ['public', 'worker', id, 'reviews'] as const,
};

/** The signed-in customer's recent review prompts (empty when signed out). */
export function useMyPrompts() {
  const signedIn = useAuthStore((state) => state.isAuthenticated);
  return useQuery<ReviewPrompt[], ApiError>({
    queryKey: reviewKeys.prompts,
    queryFn: fetchMyPrompts,
    enabled: signedIn,
    staleTime: 60_000,
  });
}

/** Open prompts the customer can still answer, newest first. */
export function useOpenPrompts(): ReviewPrompt[] {
  const prompts = useMyPrompts().data ?? [];
  return prompts.filter((prompt) => prompt.role === 'CUSTOMER' && prompt.status === 'OPEN');
}

export function useSubmitReview() {
  const client = useQueryClient();
  return useMutation<void, ApiError, ReviewSubmission>({
    mutationFn: submitReview,
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: reviewKeys.prompts });
    },
  });
}

export function usePublicWorker(workerId: string) {
  return useQuery<PublicWorker, ApiError>({
    queryKey: reviewKeys.worker(workerId),
    queryFn: () => fetchPublicWorker(workerId),
    enabled: Boolean(workerId),
    retry: (count, error) => error.status !== 404 && count < 2,
  });
}

export function usePublicReviews(workerId: string) {
  return useInfiniteQuery<PublicReviews, ApiError>({
    queryKey: reviewKeys.workerReviews(workerId),
    queryFn: ({ pageParam }) => fetchPublicReviews(workerId, pageParam as number),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.hasMore ? last.page + 1 : undefined),
    enabled: Boolean(workerId),
  });
}
