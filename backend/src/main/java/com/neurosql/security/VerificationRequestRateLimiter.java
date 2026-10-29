package com.neurosql.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Bounded process-local quota for registration and verification-resend requests.
 *
 * <p>Only keyed digests of normalized email addresses and client IPs are retained
 * in memory. Each purpose has a five-attempt/15-minute email window, while each
 * client IP shares a 30-attempt/15-minute window across purposes. This remains a
 * bounded process-local defense-in-depth layer, not a distributed limiter.</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "neurosql.auth",
        name = {"durable-sessions-enabled", "shared-rate-limit-enabled"},
        havingValue = "true")
public class VerificationRequestRateLimiter {
    static final int MAX_ATTEMPTS = 5;
    static final int MAX_IP_ATTEMPTS = 30;
    static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);
    static final Duration BLOCK_DURATION = Duration.ofMinutes(15);
    static final int MAX_TRACKED_IDENTITIES = 10_000;

    private final LoginIdentityHasher identityHasher;
    private final Clock clock;
    private final Map<String, AttemptState> states = new LinkedHashMap<>(16, 0.75f, true);

    @Autowired
    public VerificationRequestRateLimiter(
            @Value("${neurosql.auth.rate-limit-hmac-key:}") String hmacKey) {
        this(hmacKey, Clock.systemUTC());
    }

    VerificationRequestRateLimiter(String hmacKey, Clock clock) {
        this.identityHasher = new LoginIdentityHasher(hmacKey);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Checks and records the email quota only. Retained for service-level callers
     * that do not have an HTTP client address.
     */
    public synchronized Decision acquire(Purpose purpose, String email) {
        return acquireEmail(purpose, email, clock.instant());
    }

    /**
     * Applies both the purpose-specific email quota and the shared client-IP
     * quota. A valid canonical IP is required; callers should obtain it from
     * TrustedClientAddressResolver rather than reading forwarding headers.
     */
    public synchronized Decision acquire(Purpose purpose, String email, String clientAddress) {
        Objects.requireNonNull(purpose, "purpose");
        String canonicalClientAddress = TrustedClientAddressResolver.canonicalAddress(clientAddress);
        if ("unknown".equals(canonicalClientAddress)) {
            throw new IllegalArgumentException("A valid client IP address is required.");
        }

        Instant now = clock.instant();
        String ipKey = "ip:" + identityHasher.hash("ip:" + canonicalClientAddress);
        Decision ipDecision = acquireBucket(ipKey, MAX_IP_ATTEMPTS, now);
        if (!ipDecision.permitted()) {
            return ipDecision;
        }

        // Count malformed-email attempts against the IP before validating the email.
        return acquireEmail(purpose, email, now);
    }

    private Decision acquireEmail(Purpose purpose, String email, Instant now) {
        Objects.requireNonNull(purpose, "purpose");
        String normalizedEmail = JdbcAccountRepository.normalizeRegistrationEmail(email);
        String identityKey = "email:" + purpose.key() + ":" + identityHasher.hash(normalizedEmail);
        return acquireBucket(identityKey, MAX_ATTEMPTS, now);
    }

    private Decision acquireBucket(String identityKey, int maxAttempts, Instant now) {
        AttemptState state = states.get(identityKey);
        if (state != null && state.blockedUntil != null) {
            if (now.isBefore(state.blockedUntil)) {
                return Decision.throttled(ceilSeconds(Duration.between(now, state.blockedUntil)));
            }
            states.remove(identityKey);
            state = null;
        }

        if (state != null) {
            pruneFailures(state, now);
            if (state.failures.isEmpty()) {
                states.remove(identityKey);
                state = null;
            }
        }

        if (state == null) {
            if (!makeRoom(now)) {
                // Never evict a live quota or active block to admit a new identity.
                return Decision.throttled(ceilSeconds(BLOCK_DURATION));
            }
            state = new AttemptState();
            states.put(identityKey, state);
        }

        state.failures.addLast(now);
        if (state.failures.size() >= maxAttempts) {
            state.blockedUntil = now.plus(BLOCK_DURATION);
        }
        return Decision.permit();
    }

    private void pruneFailures(AttemptState state, Instant now) {
        Instant cutoff = now.minus(FAILURE_WINDOW);
        while (!state.failures.isEmpty() && !state.failures.peekFirst().isAfter(cutoff)) {
            state.failures.removeFirst();
        }
    }

    private boolean makeRoom(Instant now) {
        if (states.size() < MAX_TRACKED_IDENTITIES) {
            return true;
        }

        Iterator<Map.Entry<String, AttemptState>> entries = states.entrySet().iterator();
        while (entries.hasNext()) {
            AttemptState state = entries.next().getValue();
            if (state.blockedUntil != null) {
                if (!now.isBefore(state.blockedUntil)) {
                    entries.remove();
                }
            } else {
                pruneFailures(state, now);
                if (state.failures.isEmpty()) {
                    entries.remove();
                }
            }
            if (states.size() < MAX_TRACKED_IDENTITIES) {
                return true;
            }
        }
        return false;
    }

    private static long ceilSeconds(Duration duration) {
        long seconds = duration.getSeconds();
        return Math.max(1, duration.getNano() == 0 ? seconds : seconds + 1);
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

    private static final class AttemptState {
        private final ArrayDeque<Instant> failures = new ArrayDeque<>();
        private Instant blockedUntil;
    }
}
