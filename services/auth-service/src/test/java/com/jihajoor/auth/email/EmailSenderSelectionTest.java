package com.jihajoor.auth.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * {@code jihajoor.email.provider} selects exactly one {@link EmailSenderPort} adapter, the logging
 * one when unset (CI and every environment that has not chosen a provider).
 */
class EmailSenderSelectionTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(LoggingEmailSenderAdapter.class, FileEmailSenderAdapter.class,
                    SmtpEmailSenderAdapter.class)
            .withBean(JavaMailSender.class, () -> mock(JavaMailSender.class));

    @Test
    void unset_selectsTheLoggingAdapter() {
        context.run(ctx -> assertThat(ctx).getBean(EmailSenderPort.class)
                .isInstanceOf(LoggingEmailSenderAdapter.class));
    }

    @Test
    void logging_selectsTheLoggingAdapter() {
        context.withPropertyValues("jihajoor.email.provider=logging")
                .run(ctx -> assertThat(ctx).getBean(EmailSenderPort.class)
                        .isInstanceOf(LoggingEmailSenderAdapter.class));
    }

    @Test
    void file_selectsTheDevMailLog() {
        context.withPropertyValues("jihajoor.email.provider=file")
                .run(ctx -> assertThat(ctx).getBean(EmailSenderPort.class)
                        .isInstanceOf(FileEmailSenderAdapter.class));
    }

    @Test
    void smtp_selectsTheSmtpAdapterOnly() {
        context.withPropertyValues("jihajoor.email.provider=smtp",
                        "jihajoor.email.from=Ji-Hajoor <no-reply@jihajoor.in>")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(EmailSenderPort.class);
                    assertThat(ctx).getBean(EmailSenderPort.class).isInstanceOf(SmtpEmailSenderAdapter.class);
                });
    }

    @Test
    void smtpWithAnInvalidFromAddress_failsToStart() {
        context.withPropertyValues("jihajoor.email.provider=smtp", "jihajoor.email.from=not an address")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
