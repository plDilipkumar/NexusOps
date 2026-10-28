package com.neurosql.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded, process-local rate limiter for repeated HTTP Basic authentication failures.
 *
 * <p>This is a defence-in-depth control for the current single-instance service, not
 * a distributed limiter. A future multi-instance or multi-user deployment should move
 * this state to a shared store and review account-lockout denial-of-service trade-offs.</p>
 */
final class BasicAuthAttemptRateLimiter {
    static final int MAX_FAILED_ATTEMPTS = 5;
    static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);
    static final Duration BLOCK_DURATION = Duration.ofMinutes(15);
    private static final int MAX_TRACKED_IDENTITIES = 10_000;
    private static final String INVALID_IDENTITY = "__invalid_basic_identity__";

    private final Clock clock;
    private final Map<String, AttemptState> states = new LinkedHashMap<>(16, 0.75f, true);

    BasicAuthAttemptRateLimiter() {
        this(Clock.systemUTC());
    }

    BasicAuthAttemptRateLimiter(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    synchronized boolean isBlocked(String identity) {
        String key = normalizeIdentity(identity);
        AttemptState state = states.get(key);
        if (state == null) {
            return false;
        }

        Instant now = clock.instant();
        if (state.blockedUntil != null) {
            if (now.isBefore(state.blockedUntil)) {
                return true;
            }
            states.remove(key);
            return false;
        }

        pruneFailures(state, now);
        if (state.failures.isEmpty()) {
            states.remove(key);
        }
        return false;
    }

    synchronized void recordFailure(String identity) {
        String key = normalizeIdentity(identity);
        Instant now = clock.instant();
        AttemptState state = states.get(key);

        if (state != null && state.blockedUntil != null) {
            if (now.isBefore(state.blockedUntil)) {
                return;
            }
            states.remove(key);
            state = null;
        }

        if (state == null) {
            evictOldestIfFull();
            state = new AttemptState();
            states.put(key, state);
        }

        pruneFailures(state, now);
        state.failures.addLast(now);
        if (state.failures.size() >= MAX_FAILED_ATTEMPTS) {
            state.blockedUntil = now.plus(BLOCK_DURATION);
        }
    }

    synchronized void recordSuccess(String identity) {
        states.remove(normalizeIdentity(identity));
    }

    synchronized long retryAfterSeconds(String identity) {
        AttemptState state = states.get(normalizeIdentity(identity));
        if (state == null || state.blockedUntil == null) {
            return 0;
        }

        Duration remaining = Duration.between(clock.instant(), state.blockedUntil);
        if (remaining.isNegative() || remaining.isZero()) {
            states.remove(normalizeIdentity(identity));
            return 0;
        }

        long seconds = remaining.getSeconds();
        return Math.max(1, remaining.getNano() == 0 ? seconds : seconds + 1);
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

    private String normalizeIdentity(String identity) {
        if (identity == null || identity.isBlank() || identity.length() > 254) {
            return INVALID_IDENTITY;
        }
        return identity.strip().toLowerCase(Locale.ROOT);
    }

    private static final class AttemptState {
        private final ArrayDeque<Instant> failures = new ArrayDeque<>();
        private Instant blockedUntil;
    }
}
