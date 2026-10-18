package com.jihajoor.auth.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.ConnectException;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;

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
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * The SMTP email adapter over a mocked {@link JavaMailSender}: the message it builds (from, to,
 * subject, a multipart body with the plain text), and how each mail failure is reported. A
 * failure's message is logged by the callers, so it must never carry the address or the code.
 */
class SmtpEmailSenderAdapterTest {

    private static final String TO = "asha@example.com";

    private final JavaMailSender mailSender = mock(JavaMailSender.class);

    private SmtpEmailSenderAdapter adapter() {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        return new SmtpEmailSenderAdapter(mailSender, SmtpEmailSenderAdapter.DEFAULT_FROM);
    }

    @Test
    void sendsAMultipartMessageWithFromToSubjectAndText() throws Exception {
        EmailMessage message = EmailMessages.resetCode("482913", Duration.ofMinutes(10));

        adapter().send(TO, message);

        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(sent.capture());
        MimeMessage mime = sent.getValue();
        InternetAddress from = (InternetAddress) mime.getFrom()[0];
        assertThat(from.getAddress()).isEqualTo("no-reply@jihajoor.local");
        assertThat(from.getPersonal()).isEqualTo("Ji-Hajoor");
        assertThat(mime.getRecipients(Message.RecipientType.TO))
                .extracting(address -> ((InternetAddress) address).getAddress())
                .containsExactly(TO);
        assertThat(mime.getSubject()).isEqualTo(message.subject());
        assertThat(mime.getContent()).isInstanceOf(Multipart.class);
        assertThat(plainText(mime)).isEqualTo(message.body());
    }

    @Test
    void anAddressThatCannotBeParsed_isAPermanentFailureAndNothingIsSent() {
        SmtpEmailSenderAdapter adapter = adapter();

        assertThatThrownBy(() -> adapter.send("first@example.com, second@example.com",
                EmailMessages.alreadyRegistered()))
                .isInstanceOfSatisfying(EmailDeliveryException.class, ex -> {
                    assertThat(ex.isPermanent()).isTrue();
                    assertThat(ex.getMessage()).doesNotContain("example.com");
                });
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void parseAndPreparationFailures_arePermanent() {
        SmtpEmailSenderAdapter adapter = adapter();
        for (MailException failure : new MailException[] {
                new MailParseException("bad header"), new MailPreparationException("bad part")}) {
            doThrow(failure).when(mailSender).send(any(MimeMessage.class));

            assertThatThrownBy(() -> adapter.send(TO, EmailMessages.alreadyRegistered()))
                    .isInstanceOfSatisfying(EmailDeliveryException.class,
                            ex -> assertThat(ex.isPermanent()).isTrue());
        }
    }

    @Test
    void rejectedCredentials_areNotPermanentAndPointAtTheSmtpCredentials() {
        SmtpEmailSenderAdapter adapter = adapter();
        doThrow(new MailAuthenticationException("535 Authentication Credentials Invalid"))
                .when(mailSender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> adapter.send(TO, EmailMessages.alreadyRegistered()))
                .isInstanceOfSatisfying(EmailDeliveryException.class, ex -> {
                    assertThat(ex.isPermanent()).isFalse();
                    assertThat(ex.getMessage()).contains("authentication");
                });
    }

    @Test
    void aFailedSend_isNotPermanentAndItsMessageHoldsNeitherAddressNorCode() {
        SmtpEmailSenderAdapter adapter = adapter();
        doThrow(new MailSendException("454 Throttling failure for <" + TO + ">"))
                .when(mailSender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> adapter.send(TO, EmailMessages.resetCode("482913", Duration.ofMinutes(10))))
                .isInstanceOfSatisfying(EmailDeliveryException.class, ex -> {
                    assertThat(ex.isPermanent()).isFalse();
                    assertThat(ex.getMessage()).doesNotContain(TO).doesNotContain("482913");
                });
    }

    @Test
    void aRecipientRefusedWithA5xxReply_isPermanentAndNamesOnlyTheReplyCode() throws Exception {
        SmtpEmailSenderAdapter adapter = adapter();
        for (MailSendException refusal : new MailSendException[] {recipientRefused(550), dataRefused(554)}) {
            doThrow(refusal).when(mailSender).send(any(MimeMessage.class));

            assertThatThrownBy(() -> adapter.send(TO, EmailMessages.alreadyRegistered()))
                    .isInstanceOfSatisfying(EmailDeliveryException.class, ex -> {
                        assertThat(ex.isPermanent()).isTrue();
                        assertThat(ex.getMessage()).contains("reply 5").doesNotContain(TO);
                    });
        }
    }

    @Test
    void a4xxReplyOrNoReplyAtAll_isNotPermanent() throws Exception {
        SmtpEmailSenderAdapter adapter = adapter();
        for (MailSendException failure : new MailSendException[] {recipientRefused(452), connectionFailed(),
                dataRefused(530), dataRefused(535)}) {
            doThrow(failure).when(mailSender).send(any(MimeMessage.class));

            assertThatThrownBy(() -> adapter.send(TO, EmailMessages.alreadyRegistered()))
                    .isInstanceOfSatisfying(EmailDeliveryException.class, ex -> {
                        assertThat(ex.isPermanent()).isFalse();
                        assertThat(ex.getMessage()).doesNotContain(TO);
                    });
        }
    }

    @Test
    void aConfiguredUsername_switchesOnSmtpAuth() {
        JavaMailSenderImpl withCredentials = new JavaMailSenderImpl();
        withCredentials.setUsername("AKIAEXAMPLE");
        new SmtpEmailSenderAdapter(withCredentials, SmtpEmailSenderAdapter.DEFAULT_FROM);
        assertThat(withCredentials.getJavaMailProperties()).containsEntry("mail.smtp.auth", "true");

        JavaMailSenderImpl mailpit = new JavaMailSenderImpl();
        new SmtpEmailSenderAdapter(mailpit, SmtpEmailSenderAdapter.DEFAULT_FROM);
        assertThat(mailpit.getJavaMailProperties()).doesNotContainKey("mail.smtp.auth");
    }

    @Test
    void anInvalidFromAddress_stopsStartup() {
        assertThatThrownBy(() -> new SmtpEmailSenderAdapter(mailSender, ""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SmtpEmailSenderAdapter(mailSender, "not an address"))
                .isInstanceOf(IllegalStateException.class);
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
