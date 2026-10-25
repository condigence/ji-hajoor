package com.jihajoor.chat.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.jihajoor.chat.domain.ChatChannel;
import com.jihajoor.chat.domain.ChatMessage;

/**
 * API view of a stored chat message, used by REST history, REST send and both WebSocket transports.
 * The {@code body} is already phone-masked (Requirement 18.8).
 *
 * <p>{@code channelId} equals {@code bookingId} (a booking has exactly one channel) and
 * {@code senderRole} says which side of the booking wrote the message, so a client can lay out the
 * conversation without knowing the other participant's user id.
 */
public record MessageResponse(
        UUID id,
        UUID channelId,
        UUID bookingId,
        UUID senderId,
        String senderRole,
        String body,
        Instant sentAt) {

    public static final String CUSTOMER = "CUSTOMER";
    public static final String PROVIDER = "PROVIDER";

    public static MessageResponse from(ChatMessage message, ChatChannel channel) {
        String role = message.getSenderId().equals(channel.getCustomerId()) ? CUSTOMER : PROVIDER;
        return new MessageResponse(
                message.getId(),
                message.getBookingId(),
                message.getBookingId(),
                message.getSenderId(),
                role,
                message.getBody(),
                message.getSentAt());
    }
}
