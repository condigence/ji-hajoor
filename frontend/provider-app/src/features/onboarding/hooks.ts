import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import {
  fetchCatalog,
  fetchMyProfile,
  saveProfile,
  type CatalogCategory,
  type ProfilePayload,
  type ProviderProfile,
} from './api';

export const onboardingKeys = {
  profile: ['provider', 'profile'] as const,
  catalog: ['catalog', 'categories'] as const,
};

/** The provider's profile; resolves to null when they have not onboarded yet. */
export function useMyProfile(): UseQueryResult<ProviderProfile | null, ApiError> {
  return useQuery<ProviderProfile | null, ApiError>({
    queryKey: onboardingKeys.profile,
    queryFn: fetchMyProfile,
    staleTime: 60_000,
  });
}

export function useCatalog(): UseQueryResult<CatalogCategory[], ApiError> {
  return useQuery<CatalogCategory[], ApiError>({
    queryKey: onboardingKeys.catalog,
    queryFn: fetchCatalog,
    staleTime: 5 * 60_000,
  });
}

export function useSaveProfile(): UseMutationResult<ProviderProfile, ApiError, ProfilePayload> {
  const queryClient = useQueryClient();
  return useMutation<ProviderProfile, ApiError, ProfilePayload>({
    mutationFn: saveProfile,
    onSuccess: (profile) => {
      queryClient.setQueryData(onboardingKeys.profile, profile);
    },
  });
}
