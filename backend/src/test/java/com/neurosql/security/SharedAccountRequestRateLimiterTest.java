package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SharedAccountRequestRateLimiterTest {
    private FakeSharedLimiter shared;
    private SharedAccountRequestRateLimiter limiter;

    @BeforeEach
    void setUp() {
        shared = new FakeSharedLimiter();
        limiter = new SharedAccountRequestRateLimiter(shared);
    }

    @Test
    void usesNormalizedEmailAndRejectsTheAttemptThatCreatesTheSharedBlock() {
        for (int attempt = 0; attempt < 4; attempt++) {
            SharedAccountRequestRateLimiter.Decision decision = limiter.acquirePasswordReset(
                    attempt == 0 ? " Alice@Example.test " : "ALICE@example.test",
                    null);
            assertThat(decision.permitted()).isTrue();
            assertThat(decision.retryAfterSeconds()).isZero();
        }

        SharedAccountRequestRateLimiter.Decision blocked =
                limiter.acquirePasswordReset("alice@example.test", null);

        assertThat(blocked.permitted()).isFalse();
        assertThat(blocked.retryAfterSeconds()).isEqualTo(900);
        assertThat(shared.attempts).containsEntry(
                "neurosql:account-request:password-reset:email:alice@example.test", 5);
    }

    @Test
    void optionalClientIpQuotaIsSharedAcrossDifferentEmails() {
        for (int attempt = 0; attempt < 4; attempt++) {
            assertThat(limiter.acquirePasswordReset(
                    "person" + attempt + "@example.test", "203.0.113.7").permitted()).isTrue();
        }

        SharedAccountRequestRateLimiter.Decision blocked =
                limiter.acquirePasswordReset("another@example.test", "203.0.113.7");

        assertThat(blocked.permitted()).isFalse();
        assertThat(blocked.retryAfterSeconds()).isEqualTo(900);
        assertThat(shared.attempts).containsEntry(
                "neurosql:account-request:password-reset:ip:203.0.113.7", 5);
    }

    @Test
    void malformedClientAddressIsRejectedBeforeRecordingAttempts() {
        assertThatThrownBy(() -> limiter.acquirePasswordReset("alice@example.test", "untrusted-proxy.example"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid client IP");
        assertThat(shared.attempts).isEmpty();
    }

    private static final class FakeSharedLimiter implements DurableLoginAttemptRateLimiter {
        private final Map<String, Integer> attempts = new HashMap<>();

        @Override
        public long retryAfterSeconds(String identity) {
            return attempts.getOrDefault(identity, 0) >= 5 ? 900 : 0;
        }

        @Override
        public void recordFailure(String identity) {
            int current = attempts.getOrDefault(identity, 0);
            if (current < 5) {
                attempts.put(identity, current + 1);
            }
        }

        @Override
        public void recordSuccess(String identity) {
            attempts.remove(identity);
        }
    }
}
