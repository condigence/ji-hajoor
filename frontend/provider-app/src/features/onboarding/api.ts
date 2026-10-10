import { apiClient, isApiError } from '@api/client';

/**
 * Provider onboarding bindings: the service catalog a provider picks from, and
 * the provider's own profile (services offered, experience, service area).
 *
 * Dispatch only offers a job to a provider whose profile shares a skill tag
 * with the booked service and whose base location is within their radius of
 * the customer, so this profile is what makes a new provider bookable.
 */

export interface CatalogSubcategory {
  id: string;
  categoryId: string;
  name: string;
  basePrice: number;
  skillTags: string[];
}

export interface CatalogCategory {
  id: string;
  name: string;
  description?: string | null;
  subcategories: CatalogSubcategory[];
}

export interface ProviderProfile {
  id: string;
  displayName: string | null;
  yearsExperience: number;
  serviceRadiusKm: number;
  baseLatitude: number | null;
  baseLongitude: number | null;
  skillTags: string[];
  categories: { categoryId: string; subcategoryIds: string[] }[];
}

export interface ProfilePayload {
  displayName: string;
  yearsExperience: number;
  serviceRadiusKm: number;
  baseLatitude: number;
  baseLongitude: number;
  skillTags: string[];
  categories: { categoryId: string; subcategoryIds: string[] }[];
}

export async function fetchCatalog(): Promise<CatalogCategory[]> {
  const { data } = await apiClient.get<CatalogCategory[]>('/catalog/categories');
  return data;
}

/** The saved profile, or null for a provider who has not onboarded yet. */
export async function fetchMyProfile(): Promise<ProviderProfile | null> {
  try {
    const { data } = await apiClient.get<ProviderProfile>('/providers/me/profile');
    return data;
  } catch (error) {
    if (isApiError(error) && error.status === 404) {
      return null;
    }
    throw error;
  }
}

export async function saveProfile(payload: ProfilePayload): Promise<ProviderProfile> {
  const { data } = await apiClient.put<ProviderProfile>('/providers/me/profile', payload);
  return data;
}
