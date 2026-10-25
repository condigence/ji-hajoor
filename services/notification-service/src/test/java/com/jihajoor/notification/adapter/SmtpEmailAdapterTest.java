package com.jihajoor.notification.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.ConnectException;
import java.util.Map;
import java.util.Properties;

import com.jihajoor.notification.channel.EmailPort;
import com.jihajoor.notification.channel.NotificationDeliveryException;
import com.jihajoor.notification.channel.adapter.LoggingEmailAdapter;
import com.jihajoor.notification.channel.adapter.SmtpEmailAdapter;
import com.jihajoor.notification.config.NotificationProperties;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * The SMTP email channel adapter over a mocked {@link JavaMailSender}: the message it builds,
 * which failures are permanent (no retry) and which are retried, that no failure message carries
 * the address (it is stored in the delivery log, Requirement 26.4), and its selection by
 * {@code jihajoor.notification.email-provider}.
 */
class SmtpEmailAdapterTest {

    private static final String TO = "asha@example.com";

    private final JavaMailSender mailSender = mock(JavaMailSender.class);

    private SmtpEmailAdapter adapter() {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        return new SmtpEmailAdapter(mailSender, new NotificationProperties());
    }

    @Test
    void sendsAMultipartMessageWithFromToSubjectAndText() throws Exception {
        adapter().send(TO, "Payment received", "We received your payment for booking BR-42.");

        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(sent.capture());
        MimeMessage mime = sent.getValue();
        InternetAddress from = (InternetAddress) mime.getFrom()[0];
        assertThat(from.getAddress()).isEqualTo("no-reply@jihajoor.local");
        assertThat(from.getPersonal()).isEqualTo("Ji-Hajoor");
        assertThat(mime.getRecipients(Message.RecipientType.TO))
                .extracting(address -> ((InternetAddress) address).getAddress())
                .containsExactly(TO);
        assertThat(mime.getSubject()).isEqualTo("Payment received");
        assertThat(mime.getContent()).isInstanceOf(Multipart.class);
        assertThat(plainText(mime)).isEqualTo("We received your payment for booking BR-42.");
    }

    @Test
    void anAddressThatCannotBeParsed_isPermanentAndNothingIsSent() {
        SmtpEmailAdapter adapter = adapter();

        assertThatThrownBy(() -> adapter.send("first@example.com, second@example.com", "s", "b"))
                .isInstanceOfSatisfying(NotificationDeliveryException.class, ex -> {
                    assertThat(ex.isPermanent()).isTrue();
                    assertThat(ex.getMessage()).doesNotContain("example.com");
                });
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void parseAndPreparationFailures_arePermanent() {
        SmtpEmailAdapter adapter = adapter();
        for (MailException failure : new MailException[] {
                new MailParseException("bad header"), new MailPreparationException("bad part")}) {
            doThrow(failure).when(mailSender).send(any(MimeMessage.class));

            assertThatThrownBy(() -> adapter.send(TO, "s", "b"))
                    .isInstanceOfSatisfying(NotificationDeliveryException.class,
                            ex -> assertThat(ex.isPermanent()).isTrue());
        }
    }

    @Test
    void rejectedCredentialsAndFailedSends_areRetriedAndNameNoAddress() {
        SmtpEmailAdapter adapter = adapter();
        for (MailException failure : new MailException[] {
                new MailAuthenticationException("535 Authentication Credentials Invalid"),
                new MailSendException("454 Throttling failure for <" + TO + ">")}) {
            doThrow(failure).when(mailSender).send(any(MimeMessage.class));

            assertThatThrownBy(() -> adapter.send(TO, "s", "b"))
                    .isInstanceOfSatisfying(NotificationDeliveryException.class, ex -> {
                        assertThat(ex.isPermanent()).isFalse();
                        assertThat(ex.getMessage()).doesNotContain(TO);
                    });
        }
    }

    @Test
    void aRecipientRefusedWithA5xxReply_isPermanentAndNamesOnlyTheReplyCode() throws Exception {
        SmtpEmailAdapter adapter = adapter();
        for (MailSendException refusal : new MailSendException[] {recipientRefused(550), dataRefused(554)}) {
            doThrow(refusal).when(mailSender).send(any(MimeMessage.class));

            assertThatThrownBy(() -> adapter.send(TO, "s", "b"))
                    .isInstanceOfSatisfying(NotificationDeliveryException.class, ex -> {
                        assertThat(ex.isPermanent()).isTrue();
                        assertThat(ex.getMessage()).contains("reply 5").doesNotContain(TO);
                    });
        }
    }

    @Test
    void a4xxReplyOrNoReplyAtAll_isRetried() throws Exception {
        SmtpEmailAdapter adapter = adapter();
        for (MailSendException failure : new MailSendException[] {recipientRefused(452), connectionFailed(),
                dataRefused(530), dataRefused(535)}) {
            doThrow(failure).when(mailSender).send(any(MimeMessage.class));

            assertThatThrownBy(() -> adapter.send(TO, "s", "b"))
                    .isInstanceOfSatisfying(NotificationDeliveryException.class, ex -> {
                        assertThat(ex.isPermanent()).isFalse();
                        assertThat(ex.getMessage()).doesNotContain(TO);
                    });
        }
    }

    @Test
    void aConfiguredUsername_switchesOnSmtpAuth() {
        JavaMailSenderImpl withCredentials = new JavaMailSenderImpl();
        withCredentials.setUsername("AKIAEXAMPLE");
        new SmtpEmailAdapter(withCredentials, new NotificationProperties());
        assertThat(withCredentials.getJavaMailProperties()).containsEntry("mail.smtp.auth", "true");

        JavaMailSenderImpl mailpit = new JavaMailSenderImpl();
        new SmtpEmailAdapter(mailpit, new NotificationProperties());
        assertThat(mailpit.getJavaMailProperties()).doesNotContainKey("mail.smtp.auth");
    }

    // ---- selection by jihajoor.notification.email-provider ----

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NotificationProperties.class)
    static class PropertiesConfig {
    }

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class, LoggingEmailAdapter.class, SmtpEmailAdapter.class)
            .withBean(JavaMailSender.class, () -> mock(JavaMailSender.class));

    @Test
    void unset_selectsTheLoggingAdapter() {
        context.run(ctx -> assertThat(ctx).getBean(EmailPort.class).isInstanceOf(LoggingEmailAdapter.class));
    }

    @Test
    void smtp_selectsTheSmtpAdapterOnly() {
        context.withPropertyValues("jihajoor.notification.email-provider=smtp")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(EmailPort.class);
                    assertThat(ctx).getBean(EmailPort.class).isInstanceOf(SmtpEmailAdapter.class);
                });
    }

    @Test
    void smtpWithAnInvalidFromAddress_failsToStart() {
        context.withPropertyValues("jihajoor.notification.email-provider=smtp",
                        "jihajoor.notification.email-from=not an address")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    // ---- SMTP replies, as JavaMailSenderImpl reports them: per failed message, chained ----

    /** The server answered RCPT TO with {@code code}: 5xx lists the address as invalid, 4xx as unsent. */
    private static MailSendException recipientRefused(int code) throws Exception {
        InternetAddress recipient = new InternetAddress(TO);
        SMTPAddressFailedException refusal = new SMTPAddressFailedException(recipient, "RCPT TO:<" + TO + ">",
                code, code + " <" + TO + ">: Recipient address rejected");
        Address[] addresses = {recipient};
        SendFailedException failed = code >= 500
                ? new SendFailedException("Invalid Addresses", refusal, null, null, addresses)
                : new SendFailedException("Invalid Addresses", refusal, null, addresses, null);
        return new MailSendException(Map.<Object, Exception>of(new Object(), failed));
    }

    /** The server refused the message at DATA, e.g. SES sandbox: recipient identity not verified. */
    private static MailSendException dataRefused(int code) {
        SMTPSendFailedException refusal = new SMTPSendFailedException("DATA", code,
                code + " Message rejected: Email address is not verified: " + TO, null, null, null, null);
        return new MailSendException(Map.<Object, Exception>of(new Object(), refusal));
    }

    /** No reply at all: the server could not be reached. */
    private static MailSendException connectionFailed() {
        MessagingException cause = new MessagingException("Couldn't connect to host, port: localhost, 1025",
                new ConnectException("Connection refused"));
        return new MailSendException("Mail server connection failed", cause,
                Map.<Object, Exception>of(new Object(), cause));
    }

    /** The first text part of a (possibly nested) multipart message. */
    private static String plainText(Part part) throws Exception {
        Object content = part.getContent();
        if (content instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                String text = plainText(multipart.getBodyPart(i));
                if (text != null) {
                    return text;
                }
            }
            return null;
        }
        return content instanceof String text ? text : null;
    }
}
