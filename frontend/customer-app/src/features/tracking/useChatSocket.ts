import { useCallback, useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { refreshAccessToken } from '@api/tokenBridge';
import { useAuthStore } from '@stores/authStore';
import { buildWsUrl } from '@lib/realtime';
import { trackingKeys } from './hooks';
import type { ChatMessage } from './api';

export type ChatConnectionState = 'connecting' | 'open' | 'closed';

/** First reconnect delay; doubles per consecutive failure. */
const RECONNECT_BASE_DELAY_MS = 1_000;
/** Ceiling for the backoff, so a long outage retries once every 30 s. */
const RECONNECT_MAX_DELAY_MS = 30_000;
/** WebSocket close code for a deliberate, normal closure by either peer. */
const NORMAL_CLOSURE = 1000;

/** Close reason the server gives when it dropped the frame just sent (channel closed). */
const MESSAGE_NOT_DELIVERED = 'MESSAGE_NOT_DELIVERED';

/**
 * Whether a JWT has expired (or will within 30 s). A token that cannot be read
 * counts as not expired: only an expiry the client can see triggers a refresh.
 */
function isExpired(token: string | null): boolean {
  if (!token) return false;
  try {
    const part = token.split('.')[1] ?? '';
    const payload = JSON.parse(atob(part.replace(/-/g, '+').replace(/_/g, '/'))) as {
      exp?: number;
    };
    return typeof payload.exp === 'number' && payload.exp * 1000 < Date.now() + 30_000;
  } catch {
    return false;
  }
}

/**
 * WebSocket bridge for the in-app chat (Requirement 18). Connects to the Chat
 * Service channel, appends inbound messages to the TanStack Query history cache
 * so the message list stays a single source of truth, and exposes a `send`
 * function. Personal phone numbers are never part of the payload (Requirement
 * 18.8) — messages carry only the sender's role and body.
 *
 * A dropped connection reconnects on its own with exponential backoff (unlike
 * EventSource, WebSocket has no built-in retry) and reloads the history once it
 * is back, since messages relayed while it was down are not replayed. Each
 * attempt uses the current access token, and a handshake that never opened
 * with an expired token gets the session refreshed first.
 *
 * The channel is closed by the server once the Booking reaches
 * PAYMENT_COMPLETED/CANCELLED (Requirement 18.5). That arrives as a normal
 * closure and is deliberately *not* retried — the channel is gone for good. The
 * channel is re-read so the screen shows it closed, and a message the server
 * dropped on the way out comes back as `undelivered`.
 */
export function useChatSocket(
  channelId: string,
  bookingId?: string,
): {
  state: ChatConnectionState;
  /** Sends the text; false when the link is not open (keep the draft). */
  send: (body: string) => boolean;
  /** Text of a sent message the server did not deliver because the chat closed. */
  undelivered: string | null;
} {
  const queryClient = useQueryClient();
  const accessToken = useAuthStore((state) => state.accessToken);
  const [state, setState] = useState<ChatConnectionState>('connecting');
  const [undelivered, setUndelivered] = useState<string | null>(null);
  const socketRef = useRef<WebSocket | null>(null);
  const lastSentRef = useRef<string | null>(null);

  useEffect(() => {
    if (!channelId) return;
    if (typeof WebSocket === 'undefined') return;

    // Effect-local so cleanup can stop a pending retry, and so a re-subscribe
    // (new channel or token) always starts from a zeroed backoff.
    let disposed = false;
    let attempt = 0;
    let everOpened = false;
    let retryTimer: number | undefined;

    function scheduleReconnect() {
      const delay = Math.min(RECONNECT_BASE_DELAY_MS * 2 ** attempt, RECONNECT_MAX_DELAY_MS);
      attempt += 1;
      retryTimer = window.setTimeout(connect, delay);
    }

    function connect() {
      retryTimer = undefined;
      // Always the current token: one refreshed since this effect ran must be used.
      const token = useAuthStore.getState().accessToken ?? accessToken;
      const socket = new WebSocket(buildWsUrl(`/chat/channels/${channelId}/ws`, token));
      socketRef.current = socket;
      setState('connecting');
      let opened = false;

      socket.onopen = () => {
        opened = true;
        attempt = 0;
        setState('open');
        if (everOpened) {
          // Back after a drop: pick up whatever was said in the meantime.
          void queryClient.invalidateQueries({ queryKey: trackingKeys.chatHistory(channelId) });
        }
        everOpened = true;
      };

      socket.onmessage = (event: MessageEvent<string>) => {
        try {
          const message = JSON.parse(event.data) as ChatMessage;
          if (!message?.id) return;
          if (message.body === lastSentRef.current) lastSentRef.current = null;
          queryClient.setQueryData<ChatMessage[]>(
            trackingKeys.chatHistory(channelId),
            (prev = []) =>
              prev.some((existing) => existing.id === message.id) ? prev : [...prev, message],
          );
        } catch {
          // Ignore malformed frames.
        }
      };

      socket.onclose = (event: CloseEvent) => {
        socketRef.current = null;
        if (disposed) return;
        setState('closed');
        if (event.code === NORMAL_CLOSURE) {
          if (event.reason === MESSAGE_NOT_DELIVERED && lastSentRef.current) {
            setUndelivered(lastSentRef.current);
          }
          lastSentRef.current = null;
          if (bookingId) {
            void queryClient.invalidateQueries({ queryKey: trackingKeys.chatChannel(bookingId) });
          }
          return;
        }
        const token = useAuthStore.getState().accessToken;
        if (!opened && isExpired(token)) {
          // The handshake was refused with an access token past its expiry: nothing else
          // refreshes it while the screen is idle. The shared single-flight refresh (the one
          // the REST client uses) rotates the refresh token once; the new token re-runs this
          // effect, which depends on accessToken. A drop with a valid token (network, deploy)
          // only retries.
          void refreshAccessToken();
        }
        scheduleReconnect();
      };

      // An error is always followed by a close event, which drives the retry.
      socket.onerror = () => setState('closed');
    }

    connect();

    return () => {
      disposed = true;
      if (retryTimer !== undefined) {
        window.clearTimeout(retryTimer);
      }
      const socket = socketRef.current;
      socketRef.current = null;
      if (socket) {
        // Detach first: this close must not be mistaken for a dropped link.
        socket.onclose = null;
        socket.onerror = null;
        socket.close();
      }
    };
  }, [channelId, bookingId, accessToken, queryClient]);

  const send = useCallback((body: string) => {
    const trimmed = body.trim();
    const socket = socketRef.current;
    if (!trimmed || !socket || socket.readyState !== WebSocket.OPEN) return false;
    lastSentRef.current = trimmed;
    setUndelivered(null);
    socket.send(JSON.stringify({ body: trimmed }));
    return true;
  }, []);

  return { state, send, undelivered };
}
