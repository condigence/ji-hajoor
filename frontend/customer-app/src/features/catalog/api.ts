import { apiClient } from '@api/client';

/**
 * Service Catalog Service API bindings (Requirement 3).
 *
 * Endpoint (Service Catalog Service, routed via API Gateway):
 * - GET /catalog/categories — active categories with their active
 *   subcategories nested
 *
 * The Catalog Service only returns active categories/subcategories to
 * customer-facing APIs (Requirement 3.5) and its responses reflect DB state as
 * of no more than 300 s ago, so cache data may be served (Requirement 3.8) —
 * hence the 5-minute client-side cache used by the query hooks.
 */

/** A service category card shown on the Home screen (Requirement 3.2). */
export interface ServiceCategory {
  id: string;
  name: string;
  description?: string;
  /**
   * Icon reference as the Catalog Service names it: an icon key ("plumbing")
   * or a URL whose file name is one. Usually null, in which case the art is
   * picked from the category name; read it through {@link categoryIconKey}.
   */
  iconUrl?: string | null;
  displayOrder: number;
  /** Active subcategories, returned nested by GET /catalog/categories. */
  subcategories?: ServiceSubcategory[];
}

/** A subcategory under a category, carrying pricing attributes (Requirement 3.7). */
export interface ServiceSubcategory {
  id: string;
  categoryId: string;
  name: string;
  description?: string;
  /** Base price in the platform currency (0.01–999,999.99). */
  basePrice: number;
  /** Estimated duration in minutes (1–480). */
  estimatedDurationMin: number;
  /** Whether this subcategory can be booked as an emergency (Requirement 3.7). */
  emergencyAvailable: boolean;
}

/** GET /catalog/categories — list active service categories. */
export async function fetchCategories(): Promise<ServiceCategory[]> {
  const { data } = await apiClient.get<ServiceCategory[]>('/catalog/categories');
  // Present categories in the Admin-defined display order (Requirement 3.2).
  return [...data].sort((a, b) => a.displayOrder - b.displayOrder);
}

/**
 * The icon key carried by a category's `iconUrl`: the value itself, or the file
 * name (without extension) of a URL/path. `undefined` when the catalog has
 * none, so callers fall back to matching on the category name.
 */
export function categoryIconKey(
  category: Pick<ServiceCategory, 'iconUrl'> | null | undefined,
): string | undefined {
  const raw = category?.iconUrl?.trim();
  if (!raw) return undefined;
  const fileName = raw.split(/[?#]/)[0]?.split('/').pop() ?? '';
  return fileName.replace(/\.[a-z0-9]+$/i, '') || undefined;
}
