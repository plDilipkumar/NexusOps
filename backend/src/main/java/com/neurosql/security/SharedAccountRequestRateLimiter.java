package com.neurosql.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Shared password-reset request quota backed by the existing HMAC-keyed PostgreSQL
 * auth rate-limit table. Email and optional trusted-client-IP identities are namespaced
 * so account recovery cannot consume or clear a user's login-failure bucket.
 *
 * <p>The fifth request in a 15-minute window creates the shared block and is rejected.
 * The underlying JDBC limiter serializes each identity with row locks.</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "neurosql.auth",
        name = {"durable-sessions-enabled", "shared-rate-limit-enabled"},
        havingValue = "true")
public class SharedAccountRequestRateLimiter {
    private static final String EMAIL_PREFIX = "neurosql:account-request:password-reset:email:";
    private static final String IP_PREFIX = "neurosql:account-request:password-reset:ip:";

    private final DurableLoginAttemptRateLimiter sharedLimiter;

    public SharedAccountRequestRateLimiter(DurableLoginAttemptRateLimiter sharedLimiter) {
        this.sharedLimiter = Objects.requireNonNull(sharedLimiter, "sharedLimiter");
    }

    public Decision acquirePasswordReset(String email, String clientAddress) {
        String normalizedEmail = JdbcAccountRepository.normalizeRegistrationEmail(email);
        List<String> identities = new ArrayList<>();
        identities.add(EMAIL_PREFIX + normalizedEmail);

        if (clientAddress != null) {
            String canonicalAddress = TrustedClientAddressResolver.canonicalAddress(clientAddress);
            if ("unknown".equals(canonicalAddress)) {
                throw new IllegalArgumentException("A valid client IP address is required.");
            }
            identities.add(IP_PREFIX + canonicalAddress);
        }

        long retryAfter = retryAfter(identities);
        if (retryAfter > 0) {
            return Decision.throttled(retryAfter);
        }

        identities.forEach(sharedLimiter::recordFailure);

        // Re-check after the serialized writes so concurrent requests cannot slip
        // through after another request has reached the five-attempt threshold.
        retryAfter = retryAfter(identities);
        return retryAfter > 0 ? Decision.throttled(retryAfter) : Decision.allow();
    }

    private long retryAfter(List<String> identities) {
        return identities.stream()
                .mapToLong(sharedLimiter::retryAfterSeconds)
                .max()
                .orElse(0);
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
