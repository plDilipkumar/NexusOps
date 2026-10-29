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
    void ipQuotaIsSharedAcrossEmailsAndPurposes() {
        for (int attempt = 0; attempt < VerificationRequestRateLimiter.MAX_IP_ATTEMPTS; attempt++) {
            VerificationRequestRateLimiter.Purpose purpose = attempt % 2 == 0
                    ? VerificationRequestRateLimiter.Purpose.REGISTRATION
                    : VerificationRequestRateLimiter.Purpose.RESEND;
            VerificationRequestRateLimiter.Decision decision = limiter.acquire(
                    purpose, "person" + attempt + "@example.test", "203.0.113.7");
            assertThat(decision.permitted()).isTrue();
        }

        VerificationRequestRateLimiter.Decision blocked = limiter.acquire(
                VerificationRequestRateLimiter.Purpose.REGISTRATION,
                "another@example.test",
                "203.0.113.7");

        assertThat(blocked.permitted()).isFalse();
        assertThat(blocked.retryAfterSeconds()).isEqualTo(900);
    }

    @Test
    void emailQuotaStillAppliesAcrossDifferentClientAddresses() {
        for (int attempt = 1; attempt <= VerificationRequestRateLimiter.MAX_ATTEMPTS; attempt++) {
            VerificationRequestRateLimiter.Decision decision = limiter.acquire(
                    VerificationRequestRateLimiter.Purpose.RESEND,
                    "alice@example.test",
                    "198.51.100." + attempt);
            assertThat(decision.permitted()).isTrue();
        }

        VerificationRequestRateLimiter.Decision blocked = limiter.acquire(
                VerificationRequestRateLimiter.Purpose.RESEND,
                "alice@example.test",
                "198.51.100.6");

        assertThat(blocked.permitted()).isFalse();
        assertThat(blocked.retryAfterSeconds()).isEqualTo(900);
    }

    @Test
    void canonicalizesEquivalentIpv6AddressesToTheSameIpQuota() {
        for (int attempt = 0; attempt < VerificationRequestRateLimiter.MAX_IP_ATTEMPTS; attempt++) {
            String address = attempt % 2 == 0 ? "2001:db8::1" : "2001:0db8:0:0:0:0:0:1";
            VerificationRequestRateLimiter.Decision decision = limiter.acquire(
                    VerificationRequestRateLimiter.Purpose.REGISTRATION,
                    "ipv6-person" + attempt + "@example.test",
                    address);
            assertThat(decision.permitted()).isTrue();
        }

        VerificationRequestRateLimiter.Decision blocked = limiter.acquire(
                VerificationRequestRateLimiter.Purpose.RESEND,
                "ipv6-another@example.test",
                "2001:db8::1");
        assertThat(blocked.permitted()).isFalse();
        assertThat(blocked.retryAfterSeconds()).isEqualTo(900);
    }

    @Test
    void malformedEmailAttemptsStillConsumeTheClientIpQuota() {
        assertThatThrownBy(() -> limiter.acquire(
                VerificationRequestRateLimiter.Purpose.REGISTRATION,
                "not-an-email",
                "203.0.113.50"))
                .isInstanceOf(IllegalArgumentException.class);

        for (int attempt = 0; attempt < VerificationRequestRateLimiter.MAX_IP_ATTEMPTS - 1; attempt++) {
            VerificationRequestRateLimiter.Decision decision = limiter.acquire(
                    VerificationRequestRateLimiter.Purpose.REGISTRATION,
                    "valid" + attempt + "@example.test",
                    "203.0.113.50");
            assertThat(decision.permitted()).isTrue();
        }

        VerificationRequestRateLimiter.Decision blocked = limiter.acquire(
                VerificationRequestRateLimiter.Purpose.REGISTRATION,
                "last@example.test",
                "203.0.113.50");
        assertThat(blocked.permitted()).isFalse();
        assertThat(blocked.retryAfterSeconds()).isEqualTo(900);
    }

    @Test
    void rejectsInvalidClientAddressesInsteadOfSharingAnUnknownIpBucket() {
        assertThatThrownBy(() -> limiter.acquire(
                VerificationRequestRateLimiter.Purpose.REGISTRATION,
                "alice@example.test",
                "untrusted-proxy.example"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid client IP");

        assertThatThrownBy(() -> limiter.acquire(
                VerificationRequestRateLimiter.Purpose.REGISTRATION,
                "alice@example.test",
                "203.0.113.7, 10.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void capacityFailsClosedWithoutEvictingAnActiveEmailBlock() {
        for (int attempt = 0; attempt < VerificationRequestRateLimiter.MAX_ATTEMPTS; attempt++) {
            limiter.acquire(VerificationRequestRateLimiter.Purpose.REGISTRATION, "blocked@example.test");
        }
        clock.advanceSeconds(60);

        VerificationRequestRateLimiter.Decision lastCapacityAttempt = null;
        for (int index = 0; index < VerificationRequestRateLimiter.MAX_TRACKED_IDENTITIES; index++) {
            lastCapacityAttempt = limiter.acquire(
                    VerificationRequestRateLimiter.Purpose.REGISTRATION,
                    "filler" + index + "@example.test");
        }

        assertThat(lastCapacityAttempt).isNotNull();
        assertThat(lastCapacityAttempt.permitted()).isFalse();
        assertThat(lastCapacityAttempt.retryAfterSeconds()).isEqualTo(900);

        VerificationRequestRateLimiter.Decision existingBlock =
                limiter.acquire(VerificationRequestRateLimiter.Purpose.REGISTRATION, "blocked@example.test");
        assertThat(existingBlock.permitted()).isFalse();
        assertThat(existingBlock.retryAfterSeconds()).isEqualTo(840);
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
