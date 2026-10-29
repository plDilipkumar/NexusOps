package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VerificationEmailWorkflowServiceTest {
    private static final String EMAIL = "alice@example.test";
    private static final String PASSWORD = "correct-horse-12";
    private static final String DISPLAY_NAME = "Alice";
    private static final String RAW_TOKEN = "A".repeat(43);
    private static final String GENERIC_MESSAGE =
            "If the address can receive verification email, instructions will be sent.";

    @Mock
    private RegistrationService registration;
    @Mock
    private VerificationResendPreparationService resendPreparation;
    @Mock
    private VerificationEmailSender emailSender;

    private VerificationEmailWorkflowService service;
    private AccountActionTokenService.IssuedActionToken token;

    @BeforeEach
    void setUp() {
        service = new VerificationEmailWorkflowService(registration, resendPreparation, emailSender);
        token = new AccountActionTokenService.IssuedActionToken(
                RAW_TOKEN,
                AccountActionTokenService.Purpose.EMAIL_VERIFICATION,
                Instant.parse("2026-10-30T12:00:00Z"));
    }

    @Test
    void registrationSendsOnlyForNewAccountAndReturnsGenericResponse() {
        when(registration.register(" Alice@Example.test ", PASSWORD, "Alice"))
                .thenReturn(new RegistrationService.RegistrationAttempt(Optional.of(token)));

        VerificationEmailWorkflowService.RequestAccepted response =
                service.registerAndSend(" Alice@Example.test ", PASSWORD, "Alice");

        assertThat(response.message()).isEqualTo(GENERIC_MESSAGE);
        assertThat(response.toString()).doesNotContain(RAW_TOKEN, EMAIL);
        verify(emailSender).sendVerificationEmail(EMAIL, token);
    }

    @Test
    void duplicateRegistrationReturnsSameGenericResponseWithoutSending() {
        when(registration.register(EMAIL, PASSWORD, DISPLAY_NAME))
                .thenReturn(new RegistrationService.RegistrationAttempt(Optional.empty()));

        VerificationEmailWorkflowService.RequestAccepted response =
                service.registerAndSend(EMAIL, PASSWORD, DISPLAY_NAME);

        assertThat(response.message()).isEqualTo(GENERIC_MESSAGE);
        verify(emailSender, never()).sendVerificationEmail(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void deliveryFailureIsGenericAndCanBeRetriedWithoutLeakingProviderDetails() {
        when(registration.register(EMAIL, PASSWORD, DISPLAY_NAME))
                .thenReturn(new RegistrationService.RegistrationAttempt(Optional.of(token)));
        doThrow(new VerificationEmailSender.DeliveryFailedException())
                .when(emailSender).sendVerificationEmail(EMAIL, token);

        VerificationEmailWorkflowService.RequestAccepted response =
                service.registerAndSend(EMAIL, PASSWORD, DISPLAY_NAME);

        assertThat(response.message()).isEqualTo(GENERIC_MESSAGE);
        assertThat(response.toString()).doesNotContain(RAW_TOKEN, EMAIL);
    }

    @Test
    void resendReturnsSameGenericResponseWhenNoEligibleAccountExists() {
        when(resendPreparation.prepare("unknown@example.test")).thenReturn(Optional.empty());

        VerificationEmailWorkflowService.RequestAccepted response =
                service.resendVerification("unknown@example.test");

        assertThat(response.message()).isEqualTo(GENERIC_MESSAGE);
        verifyNoInteractions(emailSender);
    }

    @Test
    void resendSendsPreparedReplacementTokenAndDoesNotExposeIt() {
        VerificationResendPreparationService.VerificationDelivery delivery =
                new VerificationResendPreparationService.VerificationDelivery(EMAIL, token);
        when(resendPreparation.prepare(EMAIL)).thenReturn(Optional.of(delivery));

        VerificationEmailWorkflowService.RequestAccepted response = service.resendVerification(EMAIL);

        assertThat(response.message()).isEqualTo(GENERIC_MESSAGE);
        assertThat(response.toString()).doesNotContain(RAW_TOKEN, EMAIL);
        verify(emailSender).sendVerificationEmail(EMAIL, token);
    }
}
