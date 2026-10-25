package com.jihajoor.notification.channel.adapter;

import java.nio.charset.StandardCharsets;

import com.jihajoor.notification.channel.EmailPort;
import com.jihajoor.notification.channel.NotificationDeliveryException;
import com.jihajoor.notification.config.NotificationProperties;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Production {@link EmailPort} adapter: delivers booking notifications and receipts over SMTP
 * through Spring's {@link JavaMailSender}. Locally that is Mailpit (SMTP on port 1025, web UI to
 * read the mail); in AWS it is the Amazon SES SMTP interface (port 587, STARTTLS, SMTP
 * credentials). The server is configured by the {@code spring.mail.*} block of
 * {@code notification-service.yml}.
 *
 * <p>Activated with {@code jihajoor.notification.email-provider=smtp}
 * ({@code NOTIFICATION_EMAIL_PROVIDER=smtp}).
 *
 * <p>Each email goes out as a UTF-8 MIME multipart message with a plain-text part: the templates
 * are plain text, and the multipart form leaves room for an HTML alternative later.
 *
 * <p>Failures become a {@link NotificationDeliveryException} whose message names the kind of
 * failure only, since it is logged and stored in the delivery log; server replies can quote the
 * recipient address, so they stay in the cause. The recipient, subject and body are never logged
 * (Requirement 26.4).
 * <ul>
 *   <li>The message cannot be built or parsed (a malformed address, {@link MailParseException},
 *       {@link MailPreparationException}): {@linkplain NotificationDeliveryException#permanent
 *       permanent}, recorded as failed without further retries.</li>
 *   <li>The server refused it with a 5xx reply (550 no such mailbox, 554 rejected; see
 *       {@link SmtpReplies}): permanent as well.</li>
 *   <li>Rejected credentials ({@link MailAuthenticationException}) and any other
 *       {@link MailException} (server unreachable, timeout, a 4xx reply such as throttling):
 *       retried with the orchestrator's backoff, since the same message may go through later.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "jihajoor.notification", name = "email-provider", havingValue = "smtp")
public class SmtpEmailAdapter implements EmailPort {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailAdapter.class);

    private final JavaMailSender mailSender;
    private final String from;

    /**
     * @throws IllegalStateException if {@code jihajoor.notification.email-from} is not one valid
     *                               address, so a misconfigured sender stops startup instead of
     *                               failing every email
     */
    public SmtpEmailAdapter(JavaMailSender mailSender, NotificationProperties properties) {
        this.mailSender = mailSender;
        this.from = requireValidFrom(properties.getEmailFrom());
        if (mailSender instanceof JavaMailSenderImpl impl) {
            if (StringUtils.hasText(impl.getUsername())) {
                // SES (and most relays) need SMTP AUTH once credentials are configured; JavaMail
                // only authenticates when mail.smtp.auth is true, whatever username is set.
                impl.getJavaMailProperties().putIfAbsent("mail.smtp.auth", "true");
            }
            log.info("Email notifications are sent over SMTP via {}:{}{}", impl.getHost(), impl.getPort(),
                    StringUtils.hasText(impl.getUsername()) ? " with SMTP authentication" : "");
        }
    }

    @Override
    public void send(String emailAddress, String subject, String body) throws NotificationDeliveryException {
        MimeMessage mime;
        try {
            mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(emailAddress);
            helper.setSubject(subject);
            helper.setText(body, false);
        } catch (MessagingException | IllegalArgumentException ex) {
            throw NotificationDeliveryException.permanent(
                    "invalid email message: " + ex.getClass().getSimpleName(), null);
        }
        try {
            mailSender.send(mime);
        } catch (MailParseException | MailPreparationException ex) {
            throw NotificationDeliveryException.permanent("email rejected: " + ex.getClass().getSimpleName(), ex);
        } catch (MailAuthenticationException ex) {
            throw new NotificationDeliveryException(
                    "SMTP authentication failed: check the SMTP username and password", ex);
        } catch (MailSendException ex) {
            int refusal = SmtpReplies.permanentReplyCode(ex);
            if (refusal > 0) {
                throw NotificationDeliveryException.permanent(
                        "SMTP server refused the email (reply " + refusal + ")", ex);
            }
            throw new NotificationDeliveryException("SMTP send failed: " + ex.getClass().getSimpleName(), ex);
        } catch (MailException ex) {
            throw new NotificationDeliveryException("SMTP send failed: " + ex.getClass().getSimpleName(), ex);
        }
    }

    private static String requireValidFrom(String configured) {
        String from = configured == null ? "" : configured.strip();
        try {
            InternetAddress[] parsed = InternetAddress.parse(from, true);
            if (parsed.length != 1) {
                throw new AddressException("expected exactly one address");
            }
            parsed[0].validate();
            if (parsed[0].getAddress().indexOf('@') < 1) {
                throw new AddressException("expected an address with a domain");
            }
            return from;
        } catch (AddressException ex) {
            throw new IllegalStateException("jihajoor.notification.email-from (NOTIFICATION_EMAIL_FROM) is not a "
                    + "valid sender address: '" + from + "'", ex);
        }
    }
}
