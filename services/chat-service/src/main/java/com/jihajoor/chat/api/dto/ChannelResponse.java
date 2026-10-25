package com.jihajoor.chat.api.dto;

import java.util.UUID;

import com.jihajoor.chat.domain.ChatChannel;

/**
 * API view of a booking's chat channel (Requirement 18.1). {@code channelId} equals
 * {@code bookingId}; {@code active} turns false once the booking reaches PAYMENT_COMPLETED or
 * CANCELLED (Requirement 18.5), after which history stays readable but sends are refused.
 */
public record ChannelResponse(UUID channelId, UUID bookingId, boolean active) {

    public static ChannelResponse from(ChatChannel channel) {
        return new ChannelResponse(channel.getBookingId(), channel.getBookingId(), channel.isActive());
    }
}
