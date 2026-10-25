package com.jihajoor.chat.api;

import java.util.UUID;

import com.jihajoor.chat.api.dto.ChannelResponse;
import com.jihajoor.chat.service.ChatService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Resolves a booking's chat channel, which the apps read before opening the history and the
 * WebSocket (Requirement 18.1). 404 until the provider accepts; 403 to anyone but the booking's
 * customer or provider (Property 23).
 */
@RestController
public class ChatChannelController {

    private final ChatService chatService;

    public ChatChannelController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping("/chat/bookings/{bookingId}/channel")
    public ChannelResponse channel(@PathVariable UUID bookingId) {
        return ChannelResponse.from(chatService.channelFor(bookingId, CurrentUser.id()));
    }
}
