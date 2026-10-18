import { apiClient } from '@api/client';
import type { BookingStatus } from '@stores/bookingStore';

/**
 * Service History API bindings (Requirement 28.7). Combines the Booking
 * Service's booking list/detail and job photos with the Invoice Service's
 * per-booking invoice lookup and PDF download (Requirement 13). All calls flow
 * through the shared Axios client, so each carries the bearer token.
 */

/** A row in the paginated Service History list. */
export interface BookingHistoryItem {
  bookingId: string;
  referenceNumber: string;
  status: BookingStatus;
  /** Subcategory display name for the list row. */
  serviceName: string;
  /** ISO-8601 booking date (created or scheduled). */
  date: string;
  /** Final/estimated amount for the booking, in the platform currency. */
  amount: number;
  currency?: string;
}

/** One page of booking history (Requirement 28.7 — paginated list). */
export interface BookingHistoryPage {
  items: BookingHistoryItem[];
  page: number;
  pageSize: number;
  totalItems: number;
  totalPages: number;
}

/**
 * GET /bookings/history?page&pageSize — paginated booking history for the
 * authenticated Customer, newest first. `page` is 1-based; `pageSize` is at
 * most 50.
 */
export async function fetchBookingHistory(
  page: number,
  pageSize: number,
): Promise<BookingHistoryPage> {
  const { data } = await apiClient.get<BookingHistoryPage>('/bookings/history', {
    params: { page, pageSize },
  });
  return data;
}

/** Which side of the job a photo documents. */
export type PhotoKind = 'BEFORE' | 'AFTER';

/** A stored job photo as the booking detail lists it. */
export interface JobPhoto {
  id: string;
  kind: PhotoKind;
  /** ISO-8601 upload time. */
  uploadedAt: string;
}

/**
 * Detailed view of a single booking (Requirement 28.7).
 *
 * The Booking Service omits null fields: `scheduledAt` and `providerId` are
 * absent until set. `description` and `provider` are not sent today, so every
 * screen treats them as optional and hides their sections when absent. The
 * invoice is not part of the detail: it lives in the Invoice Service and is
 * looked up with {@link fetchBookingInvoice}.
 */
export interface BookingDetail {
  bookingId: string;
  referenceNumber: string;
  status: BookingStatus;
  /** Falls back to the literal "Service" when the catalog cannot resolve it. */
  serviceName: string;
  date: string;
  amount: number;
  currency?: string;
  /** True for an emergency (dispatch-now) booking. */
  emergency?: boolean;
  /** ISO-8601 time the visit is scheduled for; absent for emergency bookings. */
  scheduledAt?: string;
  /** ISO-8601 time the booking was created. */
  createdAt?: string;
  /** The assigned provider's id, once one is assigned. */
  providerId?: string;
  /** The booked subcategory — used to resolve the service name and to book again. */
  subcategoryId?: string;
  /** The service address as the customer entered it. */
  address?: string;
  /** The service address's position — frames the live map and the ETA estimate. */
  coordinates?: { latitude: number; longitude: number };
  /**
   * Before/after photos the professional attached (Requirement 11.2, 11.4).
   * Only metadata: the bytes need the bearer token, see {@link fetchJobPhoto}.
   */
  photos?: JobPhoto[];
  /** Parts and materials the professional has recorded (Requirement 11.3). */
  parts?: Array<{ id: string; itemName: string; quantity: number; unitCost: number }>;
  description?: string;
  /**
   * The local partner agency that assigned the professional, sent while the
   * booking is `PROVIDER_ASSIGNED` and waiting for their confirmation
   * (Requirement MT-6.4).
   */
  tenantName?: string;
  provider?: {
    displayName: string;
    verified: boolean;
    rating: number;
  };
}

/**
 * GET /bookings/{key} — detailed booking view. The key may be the booking id or
 * its HFX- reference; a booking that is not the caller's is a 404.
 */
export async function fetchBookingDetail(bookingId: string): Promise<BookingDetail> {
  const { data } = await apiClient.get<BookingDetail>(`/bookings/${bookingId}`);
  return data;
}

/**
 * POST /bookings/{key}/quote/approval or /quote/rejection — the customer's
 * answer to an updated quote after the professional added parts (Requirement
 * 9.7, 9.8). Approving resumes the job at the new price; declining completes
 * it at the original price.
 */
export async function decideQuote(bookingId: string, approve: boolean): Promise<void> {
  await apiClient.post(`/bookings/${bookingId}/quote/${approve ? 'approval' : 'rejection'}`);
}

/**
 * GET /bookings/{key}/photos/{photoId} — one stored job photo, under the same
 * access rule as the booking detail. Fetched as a blob because the endpoint
 * needs the bearer header, so it cannot be a plain `<img src>`. 404
 * `MEDIA_NOT_FOUND` for a photo uploaded before photos were kept.
 */
export async function fetchJobPhoto(bookingKey: string, photoId: string): Promise<Blob> {
  const { data } = await apiClient.get<Blob>(`/bookings/${bookingKey}/photos/${photoId}`, {
    responseType: 'blob',
  });
  return data;
}

/** The Invoice Service's summary of one invoice (Requirement 13.5). */
export interface InvoiceSummary {
  id: string;
  invoiceNumber: string;
  bookingId: string;
  paymentId: string;
  /** ISO-8601 time the invoice was generated. */
  generatedAt: string;
}

/**
 * GET /invoices/bookings/{bookingId} — the invoice for a paid booking. 404
 * `INVOICE_NOT_FOUND` until it has been generated (that runs shortly after the
 * payment completes) and for a booking that is not the caller's. Takes the
 * booking's id, not its HFX- reference.
 */
export async function fetchBookingInvoice(bookingId: string): Promise<InvoiceSummary> {
  const { data } = await apiClient.get<InvoiceSummary>(`/invoices/bookings/${bookingId}`);
  return data;
}

/**
 * GET /invoices/{invoiceId}/download — the invoice PDF itself. Fetched through
 * the API client as a blob (the route needs the bearer header) and handed to
 * the browser as a file download named after the invoice number.
 */
export async function downloadInvoicePdf(invoice: {
  id: string;
  invoiceNumber: string;
}): Promise<void> {
  const { data } = await apiClient.get<Blob>(`/invoices/${invoice.id}/download`, {
    responseType: 'blob',
    headers: { Accept: 'application/pdf' },
  });
  saveBlob(data, `${invoice.invoiceNumber}.pdf`);
}

/** Saves a blob through a temporary object URL and anchor click. */
function saveBlob(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = fileName;
  anchor.rel = 'noopener';
  anchor.style.display = 'none';
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  // Revoke after the click has been handled; revoking synchronously can cancel
  // the download in some browsers.
  window.setTimeout(() => URL.revokeObjectURL(url), 30_000);
}
