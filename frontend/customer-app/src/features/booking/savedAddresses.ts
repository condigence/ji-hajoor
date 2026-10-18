import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { AddressFormValues } from './schemas';

/**
 * Addresses this device has already saved with the Customer Service, so a
 * booking can reuse one instead of saving the same place again.
 *
 * The Customer Service caps a customer at 10 saved addresses. Saving a fresh
 * address on every booking therefore hit the cap after ten bookings, after
 * which every booking went out without an address and was dead-lettered by
 * dispatch. Remembering what was saved is what lets the next booking at the
 * same place reuse its id.
 *
 * The service's own list (`GET /customers/{id}/addresses`) is the source of
 * truth: `syncFromServer` replaces the device's entries with it, so addresses
 * saved on another device show up and deleted ones are never reused.
 *
 * Entries are kept per account so a shared device never offers one customer's
 * address to another.
 */
export interface SavedAddress {
  addressId: string;
  /** The address as entered, with the coordinates it was saved with. */
  address: AddressFormValues & { latitude: number; longitude: number };
}

/** Matches the Customer Service's per-customer limit. */
const MAX_SAVED_ADDRESSES = 10;

/**
 * Two detections of the same place differ by GPS jitter, so coordinates are
 * compared with a tolerance rather than exactly.
 */
const SAME_PLACE_METERS = 100;

/** One address as `GET /customers/{id}/addresses` lists it. */
export interface ServerAddress {
  addressId: string;
  label: string | null;
  lat: number;
  lng: number;
  /** The reverse-geocoded street address, when the service resolved one. */
  address: string | null;
  isDefault: boolean;
}

interface SavedAddressState {
  byUser: Record<string, SavedAddress[]>;
  remember: (userId: string, entry: SavedAddress) => void;
  syncFromServer: (userId: string, server: ServerAddress[]) => void;
}

/**
 * Form values for an address the device has not seen. The app saves the label
 * as "line1, line2, city, PIN" (see `resolveAddressId`), so it is split back;
 * anything else becomes line 1.
 */
function fromServer(server: ServerAddress): SavedAddress['address'] {
  const parts = (server.label ?? server.address ?? '').split(',').map((part) => part.trim()).filter(Boolean);
  const pin = parts.length >= 3 && /^\d{6}$/.test(parts[parts.length - 1] ?? '') ? parts.pop() : '';
  const city = parts.length >= 2 ? parts.pop() ?? '' : '';
  const [line1 = 'Saved address', ...rest] = parts;
  return {
    line1,
    line2: rest.join(', '),
    city,
    postalCode: pin ?? '',
    latitude: server.lat,
    longitude: server.lng,
  };
}

export const useSavedAddressStore = create<SavedAddressState>()(
  persist(
    (set, get) => ({
      byUser: {},
      remember: (userId, entry) => {
        const others = (get().byUser[userId] ?? []).filter(
          (saved) => saved.addressId !== entry.addressId,
        );
        set({
          byUser: {
            ...get().byUser,
            [userId]: [entry, ...others].slice(0, MAX_SAVED_ADDRESSES),
          },
        });
      },
      syncFromServer: (userId, server) => {
        const local = new Map((get().byUser[userId] ?? []).map((saved) => [saved.addressId, saved]));
        // Keep the device's own form values where it has them; the service's order
        // (default first, then newest) otherwise.
        const merged = server.map(
          (entry) => local.get(entry.addressId) ?? { addressId: entry.addressId, address: fromServer(entry) },
        );
        set({ byUser: { ...get().byUser, [userId]: merged.slice(0, MAX_SAVED_ADDRESSES) } });
      },
    }),
    { name: 'jihajoor.saved-addresses' },
  ),
);

/** The saved addresses for an account, most recently used first. */
export function savedAddressesFor(userId: string | undefined): SavedAddress[] {
  return userId ? (useSavedAddressStore.getState().byUser[userId] ?? []) : [];
}

/** Lower-cased, whitespace-collapsed text of the address lines. */
function addressText(address: AddressFormValues): string {
  return [address.line1, address.line2, address.city, address.postalCode]
    .map((part) => (part ?? '').trim().toLowerCase().replace(/\s+/g, ' '))
    .join('|');
}

/** Great-circle distance in metres between two coordinates. */
function distanceMeters(aLat: number, aLng: number, bLat: number, bLng: number): number {
  const toRad = (deg: number) => (deg * Math.PI) / 180;
  const dLat = toRad(bLat - aLat);
  const dLng = toRad(bLng - aLng);
  const h =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(aLat)) * Math.cos(toRad(bLat)) * Math.sin(dLng / 2) ** 2;
  return 2 * 6_371_000 * Math.asin(Math.sqrt(h));
}

/**
 * A saved address for the same place: same address text, and coordinates
 * within GPS jitter of each other.
 */
export function findSavedAddress(
  userId: string | undefined,
  address: AddressFormValues,
): SavedAddress | undefined {
  if (address.latitude === undefined || address.longitude === undefined) return undefined;
  const { latitude, longitude } = address;
  const text = addressText(address);
  return savedAddressesFor(userId).find(
    (saved) =>
      addressText(saved.address) === text &&
      distanceMeters(latitude, longitude, saved.address.latitude, saved.address.longitude) <=
        SAME_PLACE_METERS,
  );
}
