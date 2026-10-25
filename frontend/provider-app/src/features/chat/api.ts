import { apiClient } from '@api/client';
import { env } from '@config/env';

/**
 * Chat Service bindings (Requirement 18). A booking has one channel, opened
 * when the provider accepts and closed at PAYMENT_COMPLETED/CANCELLED; its id
 * is the booking id. History stays readable after it closes.
 */

export interface ChatChannel {
  channelId: string;
  bookingId: string;
  /** False once the Booking reaches PAYMENT_COMPLETED/CANCELLED (Requirement 18.5). */
  active: boolean;
}

/** A single chat message. Phone numbers are masked server-side (Requirement 18.8). */
export interface ChatMessage {
  id: string;
  channelId: string;
  /** Which side of the booking wrote it. */
  senderRole: 'CUSTOMER' | 'PROVIDER';
  body: string;
  /** ISO-8601 timestamp. */
  sentAt: string;
}

/** GET /chat/bookings/{bookingId}/channel — 404 until the provider accepts. */
export async function fetchChatChannel(bookingId: string): Promise<ChatChannel> {
  const { data } = await apiClient.get<ChatChannel>(`/chat/bookings/${bookingId}/channel`);
  return data;
}

/** GET /chat/channels/{channelId}/messages — history, oldest first (Requirement 18.4). */
export async function fetchChatHistory(channelId: string): Promise<ChatMessage[]> {
  const { data } = await apiClient.get<ChatMessage[]>(`/chat/channels/${channelId}/messages`);
  return data;
}

/**
 * The chat WebSocket URL for a channel. The browser WebSocket API cannot set
 * an Authorization header, so the token rides as `?access_token=`; the gateway
 * accepts it on this path only and moves it into the bearer header.
 */
export function buildChatSocketUrl(channelId: string, accessToken?: string | null): string {
  const base = env.apiBaseUrl.replace(/\/$/, '');
  const path = `${base}/chat/channels/${channelId}/ws`;
  const origin = typeof window !== 'undefined' ? window.location.origin : 'http://localhost';
  const url = /^[a-z]+:\/\//i.test(base) ? new URL(path) : new URL(path, origin);
  url.protocol =
    url.protocol === 'https:' ? 'wss:' : url.protocol === 'http:' ? 'ws:' : url.protocol;
  if (accessToken) {
    url.searchParams.set('access_token', accessToken);
  }
  return url.toString();
}
