package com.jihajoor.rating.event;

import java.util.UUID;

/**
 * Asks a customer, once, to rate a job they paid for a day ago and have not rated yet. The
 * Notification Service reads {@code customerId} to address it.
 */
public record ReviewReminderEvent(UUID bookingId, UUID customerId, UUID providerId) {

    public static final String EVENT_TYPE = "ReviewReminder";
    public static final String AGGREGATE_TYPE = "ReviewPrompt";
}
