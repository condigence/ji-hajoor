package com.jihajoor.auth.email;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * Codes and links travel in the body only. The subject is logged (by the logging adapter and on a
 * failed send) and shown by mail clients in notifications, so it must never hold either.
 */
class EmailMessagesTest {

    private static final String CODE = "482913";
    private static final Duration TTL = Duration.ofMinutes(10);

    @Test
    void codes_areInTheBodyAndNeverInTheSubject() {
        for (EmailMessage message : new EmailMessage[] {
                EmailMessages.signupCode("Asha", CODE, TTL),
                EmailMessages.resetCode(CODE, TTL),
                EmailMessages.emailChangeCode(CODE, TTL)}) {
            assertThat(message.body()).contains(CODE);
            assertThat(message.subject()).doesNotContain(CODE).doesNotContainPattern("\\d{4,}");
        }
    }

    @Test
    void theInvitationLink_isInTheBodyAndNeverInTheSubject() {
        String link = "http://localhost:5175/invite/tok_AbC123-xyz";

        EmailMessage message = EmailMessages.invitation("Ravi", "FINANCE_ADMIN", link, Duration.ofDays(7));

        assertThat(message.body()).contains(link);
        assertThat(message.subject()).doesNotContain("tok_AbC123-xyz").doesNotContain("http");
    }
}
