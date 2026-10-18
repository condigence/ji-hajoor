import { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { Alert, Box, Button, IconButton, Stack, TextField, Typography } from '@mui/material';
import ChatRoundedIcon from '@mui/icons-material/ChatRounded';
import SendRoundedIcon from '@mui/icons-material/SendRounded';
import { AppShell } from '@components/AppShell';
import { QueryStateView } from '@components/QueryStateView';
import { formatDateTime } from '@lib/format';
import { brand } from '@lib/theme';
import { useChatChannel, useChatHistory, useChatSocket } from './hooks';
import type { ChatMessage } from './api';

/**
 * Chat with the booking's customer (Requirement 18). History comes over REST,
 * new messages over the WebSocket. Neither side sees the other's phone number
 * (Requirement 18.8). Sending is disabled once the channel closes.
 */
export function ChatScreen() {
  const { bookingId = '' } = useParams();
  const navigate = useNavigate();
  const channelQuery = useChatChannel(bookingId);
  const channelId = channelQuery.data?.channelId ?? '';
  const historyQuery = useChatHistory(channelId);
  const { state, send, undelivered } = useChatSocket(channelId, bookingId);

  const [draft, setDraft] = useState('');
  const listEndRef = useRef<HTMLDivElement | null>(null);
  const messages = historyQuery.data;

  useEffect(() => {
    listEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const channelActive = channelQuery.data?.active ?? false;
  const canSend = channelActive && state === 'open' && draft.trim().length > 0;

  // The chat closed as a message went out: give the text back rather than lose it.
  // Adjusted during render (not in an effect) when `undelivered` changes.
  const [restoredUndelivered, setRestoredUndelivered] = useState<string | null>(null);
  if (undelivered !== restoredUndelivered) {
    setRestoredUndelivered(undelivered);
    if (undelivered) setDraft((current) => current || undelivered);
  }

  const handleSend = () => {
    if (!canSend) return;
    if (send(draft)) setDraft('');
  };

  return (
    <AppShell title="Chat with customer" onBack={() => void navigate(`/jobs/${bookingId}/active`)}>
      <Box sx={{ display: 'flex', flexDirection: 'column', height: 'calc(100dvh - 60px - 40px)' }}>
        {channelQuery.data && !channelActive ? (
          <Alert severity="info" sx={{ mb: 1.5 }}>
            This chat is no longer active.
            {undelivered ? ' Your last message was not delivered.' : ''}
          </Alert>
        ) : null}

        <Box sx={{ flexGrow: 1, overflowY: 'auto', minHeight: 0, py: 1 }}>
          <QueryStateView
            isLoading={channelQuery.isLoading || historyQuery.isLoading}
            isError={channelQuery.isError || historyQuery.isError}
            error={channelQuery.error ?? historyQuery.error}
            onRetry={() => {
              void channelQuery.refetch();
              void historyQuery.refetch();
            }}
            isEmpty={!messages || messages.length === 0}
            emptyMessage="No messages yet. Your number stays private."
          >
            <Stack spacing={1.25}>
              {messages?.map((message) => (
                <MessageBubble key={message.id} message={message} />
              ))}
              <div ref={listEndRef} />
            </Stack>
          </QueryStateView>
        </Box>

        <Stack
          direction="row"
          spacing={1}
          sx={{ alignItems: 'flex-end', pt: 1.5, borderTop: 1, borderColor: 'divider' }}
        >
          <TextField
            fullWidth
            multiline
            maxRows={4}
            placeholder={channelActive ? 'Type a message…' : 'Chat is closed'}
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault();
                handleSend();
              }
            }}
            disabled={!channelActive}
            slotProps={{ htmlInput: { 'aria-label': 'Message', maxLength: 4000 } }}
          />
          <IconButton
            onClick={handleSend}
            disabled={!canSend}
            aria-label="Send message"
            sx={{
              bgcolor: 'primary.main',
              color: 'primary.contrastText',
              width: 48,
              height: 48,
              '&:hover': { bgcolor: 'primary.dark' },
              '&.Mui-disabled': { bgcolor: 'action.disabledBackground' },
            }}
          >
            <SendRoundedIcon fontSize="small" />
          </IconButton>
        </Stack>
      </Box>
    </AppShell>
  );
}

function MessageBubble({ message }: { message: ChatMessage }) {
  const fromMe = message.senderRole === 'PROVIDER';
  return (
    <Box sx={{ alignSelf: fromMe ? 'flex-end' : 'flex-start', maxWidth: '80%' }}>
      <Box
        sx={{
          px: 1.75,
          py: 1.25,
          bgcolor: fromMe ? 'primary.main' : brand.canvas,
          color: fromMe ? 'primary.contrastText' : 'text.primary',
          border: fromMe ? 0 : `1px solid ${brand.line}`,
          borderRadius: '18px',
          borderBottomRightRadius: fromMe ? '4px' : '18px',
          borderBottomLeftRadius: fromMe ? '18px' : '4px',
        }}
      >
        <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
          {message.body}
        </Typography>
      </Box>
      <Typography
        variant="caption"
        sx={{
          color: 'text.secondary',
          display: 'block',
          textAlign: fromMe ? 'right' : 'left',
          mt: 0.25,
          px: 0.5,
        }}
      >
        {formatDateTime(message.sentAt)}
      </Typography>
    </Box>
  );
}

/**
 * Entry point on the active job. Disabled until the channel exists and once it
 * closes (Requirement 18.5).
 */
export function ChatButton({ bookingId, onOpen }: { bookingId: string; onOpen: () => void }) {
  const channel = useChatChannel(bookingId);
  const disabled = channel.isLoading || (channel.data ? !channel.data.active : channel.isError);

  return (
    <Button
      variant="outlined"
      size="large"
      fullWidth
      startIcon={<ChatRoundedIcon />}
      onClick={onOpen}
      disabled={disabled}
      aria-label="Open chat with the customer"
    >
      {channel.data && !channel.data.active ? 'Chat closed' : 'Message customer'}
    </Button>
  );
}
