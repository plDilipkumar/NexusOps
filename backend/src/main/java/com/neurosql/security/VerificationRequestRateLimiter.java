package com.neurosql.security;

import java.util.Objects;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Applies the shared PostgreSQL request quota to registration and verification
 * resend attempts. The caller must still return a generic response for new and
 * existing addresses. This is an email-keyed control, not a substitute for a
 * trusted client-IP limit at the HTTP boundary.
 *
 * <p>The limiter HMACs identities before persistence. Purpose prefixes keep
 * registration attempts separate from resend attempts. Each request counts
 * toward the inherited five-attempt rolling window; no success reset is used
 * because these are outbound-email request quotas, not login failures.</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "neurosql.auth",
        name = {"durable-sessions-enabled", "shared-rate-limit-enabled"},
        havingValue = "true")
public class VerificationRequestRateLimiter {
    private static final String IDENTITY_PREFIX = "account-action:";

    private final DurableLoginAttemptRateLimiter sharedLimiter;

    public VerificationRequestRateLimiter(DurableLoginAttemptRateLimiter sharedLimiter) {
        this.sharedLimiter = Objects.requireNonNull(sharedLimiter, "sharedLimiter");
    }

    /**
     * Checks and records a request for a normalized email and fixed purpose.
     * When the shared limiter is at capacity, its fail-closed error is allowed
     * to propagate rather than silently disabling throttling.
     */
    public Decision acquire(Purpose purpose, String email) {
        Objects.requireNonNull(purpose, "purpose");
        String normalizedEmail = JdbcAccountRepository.normalizeRegistrationEmail(email);
        String identity = IDENTITY_PREFIX + purpose.key() + ":" + normalizedEmail;

        long retryAfterSeconds = sharedLimiter.retryAfterSeconds(identity);
        if (retryAfterSeconds > 0) {
            return Decision.throttled(retryAfterSeconds);
        }

        sharedLimiter.recordFailure(identity);
        return Decision.permit();
    }

    public enum Purpose {
        REGISTRATION("registration"),
        RESEND("resend");

        private final String key;

        Purpose(String key) {
            this.key = key;
        }

        String key() {
            return key;
        }
    }

    public record Decision(boolean permitted, long retryAfterSeconds) {
        public Decision {
            if (retryAfterSeconds < 0
                    || (permitted && retryAfterSeconds != 0)
                    || (!permitted && retryAfterSeconds == 0)) {
                throw new IllegalArgumentException("The rate-limit decision is inconsistent.");
            }
        }

        static Decision permit() {
            return new Decision(true, 0);
        }

        static Decision throttled(long retryAfterSeconds) {
            return new Decision(false, retryAfterSeconds);
        }
    }
}
