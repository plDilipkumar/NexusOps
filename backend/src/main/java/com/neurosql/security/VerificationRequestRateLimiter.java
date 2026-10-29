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
 * <p>Only a keyed digest of the normalized email is retained in memory. Each
 * purpose has an independent five-attempt/15-minute window. This is a first
 * layer against repeated email sends, not a distributed limiter or a substitute
 * for trusted client-IP controls at the HTTP boundary.</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "neurosql.auth",
        name = {"durable-sessions-enabled", "shared-rate-limit-enabled"},
        havingValue = "true")
public class VerificationRequestRateLimiter {
    static final int MAX_ATTEMPTS = 5;
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
     * Checks and records one email-keyed request. The fifth request is accepted
     * and establishes the block, matching the existing login limiter's semantics;
     * subsequent requests receive the remaining block duration.
     */
    public synchronized Decision acquire(Purpose purpose, String email) {
        Objects.requireNonNull(purpose, "purpose");
        String normalizedEmail = JdbcAccountRepository.normalizeRegistrationEmail(email);
        String identityKey = purpose.key() + ":" + identityHasher.hash(normalizedEmail);
        Instant now = clock.instant();

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
            evictOldestIfFull();
            state = new AttemptState();
            states.put(identityKey, state);
        }

        state.failures.addLast(now);
        if (state.failures.size() >= MAX_ATTEMPTS) {
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

    private void evictOldestIfFull() {
        if (states.size() < MAX_TRACKED_IDENTITIES) {
            return;
        }
        Iterator<String> oldest = states.keySet().iterator();
        if (oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
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
