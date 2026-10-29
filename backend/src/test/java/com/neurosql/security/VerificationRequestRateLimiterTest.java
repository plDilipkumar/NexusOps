package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VerificationRequestRateLimiterTest {
    private static final String HMAC_KEY = "test-only-verification-hmac-key-32-bytes";
    private static final Instant NOW = Instant.parse("2026-10-29T11:30:00Z");

    private MutableClock clock;
    private VerificationRequestRateLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        limiter = new VerificationRequestRateLimiter(HMAC_KEY, clock);
    }

    @Test
    void normalizesEmailAndBlocksAfterFiveRegistrationRequests() {
        for (int attempt = 0; attempt < VerificationRequestRateLimiter.MAX_ATTEMPTS; attempt++) {
            String email = attempt == 0 ? " Alice@Example.test " : "alice@example.test";
            VerificationRequestRateLimiter.Decision decision =
                    limiter.acquire(VerificationRequestRateLimiter.Purpose.REGISTRATION, email);
            assertThat(decision.permitted()).isTrue();
            assertThat(decision.retryAfterSeconds()).isZero();
        }

        VerificationRequestRateLimiter.Decision blocked =
                limiter.acquire(VerificationRequestRateLimiter.Purpose.REGISTRATION, "ALICE@example.test");

        assertThat(blocked.permitted()).isFalse();
        assertThat(blocked.retryAfterSeconds()).isEqualTo(900);
    }

    @Test
    void registrationAndResendHaveIndependentQuotas() {
        for (int attempt = 0; attempt < VerificationRequestRateLimiter.MAX_ATTEMPTS; attempt++) {
            limiter.acquire(VerificationRequestRateLimiter.Purpose.REGISTRATION, "alice@example.test");
        }

        VerificationRequestRateLimiter.Decision resend =
                limiter.acquire(VerificationRequestRateLimiter.Purpose.RESEND, "alice@example.test");

        assertThat(resend.permitted()).isTrue();
    }

    @Test
    void expiredBlockStartsANewWindow() {
        for (int attempt = 0; attempt < VerificationRequestRateLimiter.MAX_ATTEMPTS; attempt++) {
            limiter.acquire(VerificationRequestRateLimiter.Purpose.RESEND, "alice@example.test");
        }
        assertThat(limiter.acquire(VerificationRequestRateLimiter.Purpose.RESEND, "alice@example.test")
                .permitted()).isFalse();

        clock.advanceSeconds(900);

        VerificationRequestRateLimiter.Decision afterExpiry =
                limiter.acquire(VerificationRequestRateLimiter.Purpose.RESEND, "alice@example.test");
        assertThat(afterExpiry.permitted()).isTrue();
    }

    @Test
    void invalidEmailIsRejected() {
        assertThatThrownBy(() -> limiter.acquire(
                VerificationRequestRateLimiter.Purpose.RESEND, "not-an-email"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingPurposeIsRejected() {
        assertThatThrownBy(() -> limiter.acquire(null, "alice@example.test"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void hmacKeyMustMeetTheExistingMinimumLengthPolicy() {
        assertThatThrownBy(() -> new VerificationRequestRateLimiter("too-short", clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 UTF-8 bytes");
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
