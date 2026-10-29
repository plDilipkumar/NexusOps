package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VerificationRequestRateLimiterTest {
    @Mock
    private DurableLoginAttemptRateLimiter sharedLimiter;

    private VerificationRequestRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new VerificationRequestRateLimiter(sharedLimiter);
    }

    @Test
    void normalizesEmailAndRecordsRegistrationRequestInItsOwnNamespace() {
        when(sharedLimiter.retryAfterSeconds("account-action:registration:alice@example.test"))
                .thenReturn(0);

        VerificationRequestRateLimiter.Decision decision =
                limiter.acquire(VerificationRequestRateLimiter.Purpose.REGISTRATION, " Alice@Example.test ");

        assertThat(decision.permitted()).isTrue();
        assertThat(decision.retryAfterSeconds()).isZero();
        verify(sharedLimiter).recordFailure("account-action:registration:alice@example.test");
    }

    @Test
    void throttledResendDoesNotRecordAnotherRequest() {
        when(sharedLimiter.retryAfterSeconds("account-action:resend:alice@example.test"))
                .thenReturn(217);

        VerificationRequestRateLimiter.Decision decision =
                limiter.acquire(VerificationRequestRateLimiter.Purpose.RESEND, "alice@example.test");

        assertThat(decision.permitted()).isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(217);
        verify(sharedLimiter, never()).recordFailure("account-action:resend:alice@example.test");
    }

    @Test
    void registrationAndResendUseSeparateRateLimitIdentities() {
        when(sharedLimiter.retryAfterSeconds("account-action:registration:alice@example.test"))
                .thenReturn(0);
        when(sharedLimiter.retryAfterSeconds("account-action:resend:alice@example.test"))
                .thenReturn(0);

        limiter.acquire(VerificationRequestRateLimiter.Purpose.REGISTRATION, "alice@example.test");
        limiter.acquire(VerificationRequestRateLimiter.Purpose.RESEND, "alice@example.test");

        verify(sharedLimiter).recordFailure("account-action:registration:alice@example.test");
        verify(sharedLimiter).recordFailure("account-action:resend:alice@example.test");
    }

    @Test
    void invalidEmailIsRejectedBeforeTouchingSharedLimiter() {
        assertThatThrownBy(() -> limiter.acquire(
                VerificationRequestRateLimiter.Purpose.RESEND, "not-an-email"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(sharedLimiter);
    }

    @Test
    void missingPurposeIsRejectedBeforeTouchingSharedLimiter() {
        assertThatThrownBy(() -> limiter.acquire(null, "alice@example.test"))
                .isInstanceOf(NullPointerException.class);

        verifyNoInteractions(sharedLimiter);
    }
}
