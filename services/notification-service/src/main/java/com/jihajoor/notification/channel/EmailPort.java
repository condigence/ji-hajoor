package com.jihajoor.notification.channel;

/**
 * Hexagonal port abstracting the outbound email vendor (Requirement 17.3).
 *
 * <p>Notification business logic depends only on this interface, never on a concrete vendor SDK.
 * Swapping the email vendor (SES, SendGrid, ...) requires only a new adapter implementing this
 * port — no change to dispatch, deduplication, preference, or retry logic. The adapters are
 * {@code log} (the default; delivers nothing) and {@code smtp} (Mailpit locally, Amazon SES SMTP in
 * AWS), selected by {@code jihajoor.notification.email-provider}.
 */
public interface EmailPort {

    /**
     * Sends an email to the supplied address.
     *
     * @param emailAddress recipient email address
     * @param subject      the email subject line
     * @param body         the email body
     * @throws NotificationDeliveryException if the vendor rejects or fails to accept the message;
     *                                       {@linkplain NotificationDeliveryException#isPermanent()
     *                                       permanent} when sending it again cannot succeed
     */
    void send(String emailAddress, String subject, String body) throws NotificationDeliveryException;
}
