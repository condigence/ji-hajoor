package com.jihajoor.auth.email;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;

import jakarta.mail.MessagingException;

import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.springframework.mail.MailSendException;

/**
 * Reads the SMTP server's reply out of a failed send, to tell a permanent refusal from a temporary
 * one. SMTP says it in the first digit: 5xx is a permanent negative reply (550 no such mailbox,
 * 553 address not allowed, 554 rejected, e.g. an SES sandbox recipient that is not verified) and
 * resending the same message gets the same answer; 4xx is temporary (451 try later, 452 mailbox
 * full, 454 throttling) and may succeed later.
 *
 * <p>JavaMail reports the reply as an {@link SMTPAddressFailedException} (RCPT TO refused) or an
 * {@link SMTPSendFailedException} (MAIL FROM or DATA refused), chained inside the exception
 * {@link MailSendException} holds for each failed message. A failure without a reply (connection
 * refused, timeout, TLS handshake) is never permanent.
 *
 * <p>Neither are 530 (authentication or STARTTLS required) and 535 (credentials refused): they
 * refuse every message the same way because the sender is misconfigured (SMTP_STARTTLS off,
 * wrong SMTP_USERNAME/SMTP_PASSWORD), not because of this message, and succeed once the settings
 * are fixed, so the message is kept for a retry.
 */
final class SmtpReplies {

    /** Replies that refuse the sender's session, not the message: see the class comment. */
    private static final Set<Integer> CONFIGURATION_REFUSALS = Set.of(530, 535);

    private SmtpReplies() {
    }

    /**
     * @return the 5xx reply code that refused the message, or 0 when the failure may succeed later:
     *         no SMTP reply at all, or any 4xx reply among them
     */
    static int permanentReplyCode(MailSendException failure) {
        Deque<Throwable> pending = new ArrayDeque<>(failure.getFailedMessages().values());
        if (failure.getCause() != null) {
            pending.add(failure.getCause());
        }
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int permanent = 0;
        while (!pending.isEmpty()) {
            Throwable next = pending.pop();
            if (!seen.add(next)) {
                continue;
            }
            int code = replyCode(next);
            if ((code >= 400 && code < 500) || CONFIGURATION_REFUSALS.contains(code)) {
                return 0;
            }
            if (code >= 500 && code < 600) {
                permanent = code;
            }
            if (next.getCause() != null) {
                pending.add(next.getCause());
            }
            if (next instanceof MessagingException messaging && messaging.getNextException() != null) {
                pending.add(messaging.getNextException());
            }
        }
        return permanent;
    }

    private static int replyCode(Throwable failure) {
        if (failure instanceof SMTPAddressFailedException address) {
            return address.getReturnCode();
        }
        if (failure instanceof SMTPSendFailedException send) {
            return send.getReturnCode();
        }
        return -1;
    }
}
