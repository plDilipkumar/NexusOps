package com.neurosql.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Shared account-lifecycle quotas backed by the HMAC-keyed PostgreSQL rate-limit table.
 * Registration and verification-resend have independent email buckets and share a
 * 30-attempt client-IP bucket. Password reset keeps its existing five-attempt buckets.
 * Email-verification consumption has a separate optional client-IP bucket.
 *
 * <p>IP identities are accepted only from TrustedClientAddressResolver. Callers pass
 * {@code null} when trusted proxy ranges are not configured; arbitrary forwarding
 * headers are never used as a fallback.</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "neurosql.auth",
        name = {"durable-sessions-enabled", "shared-rate-limit-enabled"},
        havingValue = "true")
public class SharedAccountRequestRateLimiter {
    private static final String LIFECYCLE_EMAIL_PREFIX = "neurosql:account-lifecycle:email:";
    private static final String LIFECYCLE_IP_PREFIX = "neurosql:account-lifecycle:ip:";
    private static final String PASSWORD_RESET_EMAIL_PREFIX = "neurosql:account-request:password-reset:email:";
    private static final String PASSWORD_RESET_IP_PREFIX = "neurosql:account-request:password-reset:ip:";
    private static final String VERIFICATION_CONSUME_IP_PREFIX =
            "neurosql:account-lifecycle:verification-consume:ip:";
    private static final String AI_SQL_USER_PREFIX = "neurosql:ai-sql-generation:user:";

    static final int EMAIL_MAX_ATTEMPTS = 5;
    static final int LIFECYCLE_IP_MAX_ATTEMPTS = 30;
    static final int PASSWORD_RESET_MAX_ATTEMPTS = 5;
    static final int VERIFICATION_CONSUME_IP_MAX_ATTEMPTS = 30;

    private final DurableLoginAttemptRateLimiter sharedLimiter;

    public SharedAccountRequestRateLimiter(DurableLoginAttemptRateLimiter sharedLimiter) {
        this.sharedLimiter = Objects.requireNonNull(sharedLimiter, "sharedLimiter");
    }

    public Decision acquireRegistration(String email, String clientAddress) {
        return acquireLifecycle(Purpose.REGISTRATION, email, clientAddress);
    }

    public Decision acquireVerificationResend(String email, String clientAddress) {
        return acquireLifecycle(Purpose.VERIFICATION_RESEND, email, clientAddress);
    }

    public Decision acquirePasswordReset(String email, String clientAddress) {
        String normalizedEmail = JdbcAccountRepository.normalizeRegistrationEmail(email);
        List<Bucket> buckets = new ArrayList<>();
        buckets.add(new Bucket(PASSWORD_RESET_EMAIL_PREFIX + normalizedEmail, PASSWORD_RESET_MAX_ATTEMPTS));
        addClientAddressBucket(
                buckets, PASSWORD_RESET_IP_PREFIX, clientAddress, PASSWORD_RESET_MAX_ATTEMPTS);
        return acquire(buckets);
    }

    /**
     * Applies a bounded IP quota to token-consumption attempts only when the caller
     * has resolved a client address through explicitly configured trusted proxies.
     */
    public Decision acquireVerificationConsumption(String clientAddress) {
        if (clientAddress == null) {
            return Decision.allow();
        }
        List<Bucket> buckets = new ArrayList<>();
        addClientAddressBucket(
                buckets,
                VERIFICATION_CONSUME_IP_PREFIX,
                clientAddress,
                VERIFICATION_CONSUME_IP_MAX_ATTEMPTS);
        return acquire(buckets);
    }

    /**
     * Applies one shared five-attempt quota per authenticated user to all AI SQL
     * operations, including generation and explanation, across restarts and instances.
     */
    public Decision acquireAiSqlGeneration(UUID userId) {
        return acquireAiSqlRequest(userId);
    }

    public Decision acquireAiSqlRequest(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        return acquire(List.of(new Bucket(AI_SQL_USER_PREFIX + userId, 5)));
    }

    private Decision acquireLifecycle(Purpose purpose, String email, String clientAddress) {
        Objects.requireNonNull(purpose, "purpose");
        String normalizedEmail = JdbcAccountRepository.normalizeRegistrationEmail(email);
        List<Bucket> buckets = new ArrayList<>();
        buckets.add(new Bucket(
                LIFECYCLE_EMAIL_PREFIX + purpose.key() + ":" + normalizedEmail,
                EMAIL_MAX_ATTEMPTS));
        addClientAddressBucket(
                buckets, LIFECYCLE_IP_PREFIX, clientAddress, LIFECYCLE_IP_MAX_ATTEMPTS);
        return acquire(buckets);
    }

    private static void addClientAddressBucket(
            List<Bucket> buckets,
            String prefix,
            String clientAddress,
            int maxAttempts) {
        if (clientAddress == null) {
            return;
        }
        String canonicalAddress = TrustedClientAddressResolver.canonicalAddress(clientAddress);
        if ("unknown".equals(canonicalAddress)) {
            throw new IllegalArgumentException("A valid client IP address is required.");
        }
        buckets.add(new Bucket(prefix + canonicalAddress, maxAttempts));
    }

    private Decision acquire(List<Bucket> buckets) {
        long retryAfter = retryAfter(buckets);
        if (retryAfter > 0) {
            return Decision.throttled(retryAfter);
        }

        buckets.forEach(bucket -> sharedLimiter.recordFailure(bucket.identity(), bucket.maxAttempts()));

        // Re-check after the serialized writes so concurrent requests cannot pass
        // after another instance has reached the shared threshold.
        retryAfter = retryAfter(buckets);
        return retryAfter > 0 ? Decision.throttled(retryAfter) : Decision.allow();
    }

    private long retryAfter(List<Bucket> buckets) {
        return buckets.stream()
                .mapToLong(bucket -> sharedLimiter.retryAfterSeconds(bucket.identity()))
                .max()
                .orElse(0);
    }

    public enum Purpose {
        REGISTRATION("registration"),
        VERIFICATION_RESEND("verification-resend");

        private final String key;

        Purpose(String key) {
            this.key = key;
        }

        String key() {
            return key;
        }
    }

    private record Bucket(String identity, int maxAttempts) {
        private Bucket {
            Objects.requireNonNull(identity, "identity");
            if (maxAttempts < 2 || maxAttempts > 30) {
                throw new IllegalArgumentException("The account-request quota is outside its supported range.");
            }
        }
    }

    public record Decision(boolean permitted, long retryAfterSeconds) {
        public Decision {
            if (retryAfterSeconds < 0
                    || (permitted && retryAfterSeconds != 0)
                    || (!permitted && retryAfterSeconds == 0)) {
                throw new IllegalArgumentException("The shared account-request decision is inconsistent.");
            }
        }

        static Decision allow() {
            return new Decision(true, 0);
        }

        static Decision throttled(long retryAfterSeconds) {
            return new Decision(false, Math.max(1, retryAfterSeconds));
        }
    }
}
