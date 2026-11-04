package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PasswordResetEmailWorkflowServiceTest {
    private static final String EMAIL = "alice@example.test";
    private static final String RAW_TOKEN = "A".repeat(43);
    private static final String GENERIC_MESSAGE =
            "If an account exists for that address, a password-reset link will be sent.";

    @Mock
    private PasswordResetRequestPreparationService preparation;
    @Mock
    private PasswordResetEmailSender emailSender;

    private PasswordResetEmailWorkflowService service;
    private PasswordResetRequestPreparationService.PasswordResetDelivery delivery;

    @BeforeEach
    void setUp() {
        service = new PasswordResetEmailWorkflowService(preparation, emailSender);
        AccountActionTokenService.IssuedActionToken token =
                new AccountActionTokenService.IssuedActionToken(
                        RAW_TOKEN,
                        AccountActionTokenService.Purpose.PASSWORD_RESET,
                        Instant.parse("2026-11-03T08:00:00Z"));
        delivery = new PasswordResetRequestPreparationService.PasswordResetDelivery(EMAIL, token);
    }

    @Test
    void sendsOnlyForEligibleAccountAndReturnsGenericResponse() {
        when(preparation.prepare(EMAIL)).thenReturn(Optional.of(delivery));

        PasswordResetEmailWorkflowService.RequestAccepted result = service.requestReset(EMAIL);

        assertThat(result.message()).isEqualTo(GENERIC_MESSAGE);
        assertThat(result.toString()).doesNotContain(EMAIL, RAW_TOKEN);
        verify(emailSender).sendPasswordResetEmail(EMAIL, delivery.token());
    }

    @Test
    void unknownAccountReturnsSameGenericResponseWithoutSending() {
        when(preparation.prepare(EMAIL)).thenReturn(Optional.empty());

        PasswordResetEmailWorkflowService.RequestAccepted result = service.requestReset(EMAIL);

        assertThat(result.message()).isEqualTo(GENERIC_MESSAGE);
        verifyNoInteractions(emailSender);
    }

    @Test
    void providerFailureReturnsSameGenericResponseWithoutLeakingDetails() {
        when(preparation.prepare(EMAIL)).thenReturn(Optional.of(delivery));
        doThrow(new PasswordResetEmailSender.DeliveryFailedException())
                .when(emailSender).sendPasswordResetEmail(EMAIL, delivery.token());

        PasswordResetEmailWorkflowService.RequestAccepted result = service.requestReset(EMAIL);

        assertThat(result.message()).isEqualTo(GENERIC_MESSAGE);
        assertThat(result.toString()).doesNotContain(EMAIL, RAW_TOKEN);
        verify(emailSender).sendPasswordResetEmail(EMAIL, delivery.token());
    }

    @Test
    void doesNotAttemptDeliveryWhenPreparationReturnsEmpty() {
        when(preparation.prepare(EMAIL)).thenReturn(Optional.empty());

        service.requestReset(EMAIL);

        verify(emailSender, never()).sendPasswordResetEmail(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }
}
