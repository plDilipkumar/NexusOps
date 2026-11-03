package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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
    void registrationAndVerificationResendUseIndependentEmailBuckets() {
        for (int attempt = 0; attempt < 4; attempt++) {
            assertThat(limiter.acquireRegistration(
                    attempt == 0 ? " Alice@Example.test " : "ALICE@example.test",
                    null).permitted()).isTrue();
        }

        SharedAccountRequestRateLimiter.Decision blocked =
                limiter.acquireRegistration("alice@example.test", null);
        SharedAccountRequestRateLimiter.Decision resend =
                limiter.acquireVerificationResend("alice@example.test", null);

        assertThat(blocked.permitted()).isFalse();
        assertThat(blocked.retryAfterSeconds()).isEqualTo(900);
        assertThat(resend.permitted()).isTrue();
        assertThat(shared.thresholds).containsEntry(
                "neurosql:account-lifecycle:email:registration:alice@example.test", 5);
        assertThat(shared.thresholds).containsEntry(
                "neurosql:account-lifecycle:email:verification-resend:alice@example.test", 5);
    }

    @Test
    void registrationAndResendShareThirtyAttemptClientIpBucket() {
        String ip = "203.0.113.7";
        for (int attempt = 0; attempt < 29; attempt++) {
            SharedAccountRequestRateLimiter.Decision decision = attempt % 2 == 0
                    ? limiter.acquireRegistration("person" + attempt + "@example.test", ip)
                    : limiter.acquireVerificationResend("person" + attempt + "@example.test", ip);
            assertThat(decision.permitted()).isTrue();
        }

        SharedAccountRequestRateLimiter.Decision blocked =
                limiter.acquireVerificationResend("another@example.test", ip);

        assertThat(blocked.permitted()).isFalse();
        assertThat(blocked.retryAfterSeconds()).isEqualTo(900);
        assertThat(shared.attempts).containsEntry(
                "neurosql:account-lifecycle:ip:203.0.113.7", 30);
        assertThat(shared.thresholds).containsEntry(
                "neurosql:account-lifecycle:ip:203.0.113.7", 30);
    }

    @Test
    void verificationConsumptionHasAnIndependentThirtyAttemptIpBucket() {
        String ip = "2001:db8::1";
        for (int attempt = 0; attempt < 29; attempt++) {
            assertThat(limiter.acquireVerificationConsumption(ip).permitted()).isTrue();
        }

        SharedAccountRequestRateLimiter.Decision blocked =
                limiter.acquireVerificationConsumption("2001:0db8:0:0:0:0:0:1");

        assertThat(blocked.permitted()).isFalse();
        assertThat(shared.attempts).containsEntry(
                "neurosql:account-lifecycle:verification-consume:ip:2001:db8:0:0:0:0:0:1", 30);
        // Token-consumption attempts do not consume the registration/resend IP bucket.
        assertThat(limiter.acquireRegistration("new@example.test", ip).permitted()).isTrue();
    }

    @Test
    void passwordResetKeepsItsOwnFiveAttemptBuckets() {
        for (int attempt = 0; attempt < 4; attempt++) {
            assertThat(limiter.acquirePasswordReset("reset@example.test", null).permitted()).isTrue();
        }

        assertThat(limiter.acquirePasswordReset("RESET@example.test", null).permitted()).isFalse();
        assertThat(limiter.acquireRegistration("reset@example.test", null).permitted()).isTrue();
    }

    @Test
    void aiSqlGenerationUsesDedicatedFiveAttemptPerUserBucket() {
        UUID userId = UUID.fromString("b4f41d45-68dc-4f7b-8df0-48e3f29e54c1");
        for (int attempt = 0; attempt < 4; attempt++) {
            assertThat(limiter.acquireAiSqlGeneration(userId).permitted()).isTrue();
        }

        SharedAccountRequestRateLimiter.Decision blocked = limiter.acquireAiSqlGeneration(userId);

        assertThat(blocked.permitted()).isFalse();
        assertThat(blocked.retryAfterSeconds()).isEqualTo(900);
        assertThat(shared.attempts).containsEntry(
                "neurosql:ai-sql-generation:user:" + userId, 5);
        assertThat(shared.thresholds).containsEntry(
                "neurosql:ai-sql-generation:user:" + userId, 5);
        assertThat(limiter.acquireRegistration("alice@example.test", null).permitted()).isTrue();
    }

    @Test
    void aiSqlGenerationAndExplanationShareOneFiveAttemptUserQuota() {
        UUID userId = UUID.fromString("b4f41d45-68dc-4f7b-8df0-48e3f29e54c1");
        for (int attempt = 0; attempt < 4; attempt++) {
            SharedAccountRequestRateLimiter.Decision decision = attempt % 2 == 0
                    ? limiter.acquireAiSqlGeneration(userId)
                    : limiter.acquireAiSqlRequest(userId);
            assertThat(decision.permitted()).isTrue();
        }

        SharedAccountRequestRateLimiter.Decision blocked = limiter.acquireAiSqlRequest(userId);

        assertThat(blocked.permitted()).isFalse();
        assertThat(blocked.retryAfterSeconds()).isEqualTo(900);
        assertThat(shared.attempts).containsEntry("neurosql:ai-sql-generation:user:" + userId, 5);
        assertThat(shared.thresholds).containsEntry("neurosql:ai-sql-generation:user:" + userId, 5);
    }

    @Test
    void missingTrustedClientAddressDoesNotCreateAnIpBucket() {
        assertThat(limiter.acquireRegistration("alice@example.test", null).permitted()).isTrue();
        assertThat(limiter.acquireVerificationConsumption(null).permitted()).isTrue();
        assertThat(shared.attempts).containsOnlyKeys(
                "neurosql:account-lifecycle:email:registration:alice@example.test");
    }

    @Test
    void malformedClientAddressIsRejectedBeforeRecordingAttempts() {
        assertThatThrownBy(() -> limiter.acquireRegistration(
                "alice@example.test", "untrusted-proxy.example"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid client IP");
        assertThat(shared.attempts).isEmpty();
    }

    private static final class FakeSharedLimiter implements DurableLoginAttemptRateLimiter {
        private final Map<String, Integer> attempts = new HashMap<>();
        private final Map<String, Integer> thresholds = new HashMap<>();

        @Override
        public long retryAfterSeconds(String identity) {
            return attempts.getOrDefault(identity, 0) >= thresholds.getOrDefault(identity, 5) ? 900 : 0;
        }

        @Override
        public void recordFailure(String identity) {
            recordFailure(identity, 5);
        }

        @Override
        public void recordFailure(String identity, int maxAttempts) {
            thresholds.put(identity, maxAttempts);
            int current = attempts.getOrDefault(identity, 0);
            if (current < maxAttempts) {
                attempts.put(identity, current + 1);
            }
        }

        @Override
        public void recordSuccess(String identity) {
            attempts.remove(identity);
            thresholds.remove(identity);
        }
    }
}
