package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

@ExtendWith(MockitoExtension.class)
class SmtpVerificationEmailSenderTest {
    private static final String TOKEN = "A".repeat(43);
    private static final String FROM = "no-reply@neurosql.example";
    private static final String VERIFICATION_URL = "https://neurosql.vercel.app/verify-email";

    @Mock
    private JavaMailSender mailSender;

    @Test
    void sendsVerificationLinkToNormalizedRecipient() {
        SmtpVerificationEmailSender sender = sender(VERIFICATION_URL);
        AccountActionTokenService.IssuedActionToken token =
                new AccountActionTokenService.IssuedActionToken(
                        TOKEN,
                        AccountActionTokenService.Purpose.EMAIL_VERIFICATION,
                        Instant.parse("2026-10-30T12:00:00Z"));

        sender.sendVerificationEmail(" Alice@Example.test ", token);

        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(message.capture());
        assertThat(message.getValue().getFrom()).isEqualTo(FROM);
        assertThat(message.getValue().getTo()).containsExactly("alice@example.test");
        assertThat(message.getValue().getSubject()).isEqualTo("Verify your NeuroSQL email");
        assertThat(message.getValue().getText())
                .contains(VERIFICATION_URL + "?token=" + TOKEN)
                .contains("expires in 24 hours");
    }

    @Test
    void refusesPasswordResetTokenBeforeSending() {
        SmtpVerificationEmailSender sender = sender(VERIFICATION_URL);
        AccountActionTokenService.IssuedActionToken token =
                new AccountActionTokenService.IssuedActionToken(
                        TOKEN,
                        AccountActionTokenService.Purpose.PASSWORD_RESET,
                        Instant.parse("2026-10-30T12:00:00Z"));

        assertThatThrownBy(() -> sender.sendVerificationEmail("alice@example.test", token))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(mailSender);
    }

    @Test
    void rejectsUnsafeVerificationUrls() {
        assertThatThrownBy(() -> sender("http://neurosql.vercel.app/verify-email"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A valid HTTPS verification URL must be configured.");
        assertThatThrownBy(() -> sender("https://neurosql.vercel.app/verify-email?next=https://evil.test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A valid HTTPS verification URL must be configured.");
        verifyNoInteractions(mailSender);
    }

    @Test
    void providerFailureDoesNotExposeTokenOrProviderDetails() {
        SmtpVerificationEmailSender sender = sender(VERIFICATION_URL);
        AccountActionTokenService.IssuedActionToken token =
                new AccountActionTokenService.IssuedActionToken(
                        TOKEN,
                        AccountActionTokenService.Purpose.EMAIL_VERIFICATION,
                        Instant.parse("2026-10-30T12:00:00Z"));
        doThrow(new MailSendException("provider secret detail"))
                .when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> sender.sendVerificationEmail("alice@example.test", token))
                .isInstanceOf(SmtpVerificationEmailSender.DeliveryFailedException.class)
                .hasMessage("Verification email delivery failed.")
                .satisfies(error -> assertThat(error.getMessage())
                        .doesNotContain(TOKEN, "provider secret detail"));
    }

    private SmtpVerificationEmailSender sender(String verificationUrl) {
        return new SmtpVerificationEmailSender(mailSender, FROM, verificationUrl);
    }
}
