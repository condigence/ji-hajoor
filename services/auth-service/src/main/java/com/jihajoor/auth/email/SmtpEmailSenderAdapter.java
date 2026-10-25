package com.jihajoor.auth.email;

import java.nio.charset.StandardCharsets;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * Production {@link EmailSenderPort} adapter: delivers each email over SMTP through Spring's
 * {@link JavaMailSender}. Locally that is Mailpit (SMTP on port 1025, web UI to read the mail); in
 * AWS it is the Amazon SES SMTP interface (port 587, STARTTLS, SMTP credentials). The server is
 * configured by the {@code spring.mail.*} block of {@code auth-service.yml}.
 *
 * <p>Activated with {@code jihajoor.email.provider=smtp} ({@code EMAIL_PROVIDER=smtp}).
 *
 * <p>Each email goes out as a UTF-8 MIME multipart message with a plain-text part. The emails are
 * plain text today ({@link EmailMessages}); the multipart form means an HTML version can be added
 * later as an alternative part without changing how the message is built.
 *
 * <p>Failures are reported as an {@link EmailDeliveryException} whose message names the kind of
 * failure only. Server replies can quote the recipient address, so they stay in the exception's
 * cause and are never put into the message the callers log. The recipient, subject (it can hold a
 * code) and body are never logged here. What a failure means to the user is the caller's decision,
 * unchanged by this adapter: sign-up, invitations and email changes answer 502
 * {@code EMAIL_DELIVERY_FAILED}; password reset and agency decisions log it and answer as usual.
 * <ul>
 *   <li>The message cannot be built or parsed (a malformed address, {@link MailParseException},
 *       {@link MailPreparationException}): permanent, sending it again cannot help.</li>
 *   <li>The server refused it with a 5xx reply (550 no such mailbox, 554 rejected; see
 *       {@link SmtpReplies}): permanent.</li>
 *   <li>Rejected credentials ({@link MailAuthenticationException}): not permanent, the message is
 *       fine and goes through once the SMTP credentials are corrected.</li>
 *   <li>Any other {@link MailException} (server unreachable, timeout, a 4xx reply such as
 *       throttling): not permanent, the same message may go through later.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "jihajoor.email", name = "provider", havingValue = "smtp")
public class SmtpEmailSenderAdapter implements EmailSenderPort {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSenderAdapter.class);

    /** The sender when {@code jihajoor.email.from} is not set; matches {@code auth-service.yml}. */
    static final String DEFAULT_FROM = "Ji-Hajoor <no-reply@jihajoor.local>";

    private final JavaMailSender mailSender;
    private final String from;

    /**
     * @param mailSender the sender Spring Boot builds from {@code spring.mail.*}
     * @param from       the From header, e.g. {@code Ji-Hajoor <no-reply@jihajoor.in>}; with SES it
     *                   must be a verified identity
     * @throws IllegalStateException if {@code from} is not one valid address, so a misconfigured
     *                               sender stops startup instead of failing every email
     */
    public SmtpEmailSenderAdapter(JavaMailSender mailSender,
                                  @Value("${jihajoor.email.from:" + DEFAULT_FROM + "}") String from) {
        this.mailSender = mailSender;
        this.from = requireValidFrom(from);
        if (mailSender instanceof JavaMailSenderImpl impl) {
            if (StringUtils.hasText(impl.getUsername())) {
                // SES (and most relays) need SMTP AUTH once credentials are configured; JavaMail
                // only authenticates when mail.smtp.auth is true, whatever username is set.
                impl.getJavaMailProperties().putIfAbsent("mail.smtp.auth", "true");
            }
            log.info("Emails are sent over SMTP via {}:{}{}", impl.getHost(), impl.getPort(),
                    StringUtils.hasText(impl.getUsername()) ? " with SMTP authentication" : "");
        }
    }

    @Override
    public void send(String to, EmailMessage message) {
        MimeMessage mime;
        try {
            mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(message.subject());
            helper.setText(message.body(), false);
        } catch (MessagingException | IllegalArgumentException ex) {
            throw new EmailDeliveryException("Invalid email message: " + ex.getClass().getSimpleName(), null, true);
        }
        try {
            mailSender.send(mime);
        } catch (MailParseException | MailPreparationException ex) {
            throw new EmailDeliveryException("Email rejected: " + ex.getClass().getSimpleName(), ex, true);
        } catch (MailAuthenticationException ex) {
            throw new EmailDeliveryException(
                    "SMTP authentication failed: check the SMTP username and password", ex, false);
        } catch (MailSendException ex) {
            int refusal = SmtpReplies.permanentReplyCode(ex);
            if (refusal > 0) {
                throw new EmailDeliveryException("SMTP server refused the email (reply " + refusal + ")", ex, true);
            }
            throw new EmailDeliveryException("SMTP send failed: " + ex.getClass().getSimpleName(), ex, false);
        } catch (MailException ex) {
            throw new EmailDeliveryException("SMTP send failed: " + ex.getClass().getSimpleName(), ex, false);
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
            throw new IllegalStateException("jihajoor.email.from (EMAIL_FROM) is not a valid sender address: '"
                    + from + "'", ex);
        }
    }
}
