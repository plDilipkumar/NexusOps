package com.neurosql.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Fixed-lifetime policy for opaque server-side sessions.
 *
 * <p>Sessions expire after twelve hours and are not sliding. A session is
 * active only while it is unexpired, not revoked, and within the maximum
 * lifetime measured from its recorded issue time.</p>
 *
 * <p>This policy is intentionally not wired into HTTP authentication or
 * persistence until the durable-auth schema is approved.</p>
 */
public final class SessionLifetimePolicy {
    public static final Duration MAX_SESSION_LIFETIME = Duration.ofHours(12);

    private final Clock clock;

    public SessionLifetimePolicy() {
        this(Clock.systemUTC());
    }

    SessionLifetimePolicy(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Calculates the fixed expiry for a session issued at the supplied instant. */
    public Instant expiresAt(Instant issuedAt) {
        return Objects.requireNonNull(issuedAt, "issuedAt").plus(MAX_SESSION_LIFETIME);
    }

    /**
     * Checks whether a stored session is currently usable.
     *
     * <p>Malformed or inconsistent timestamps fail closed. Any non-null
     * revocation timestamp means the session is revoked.</p>
     */
    public boolean isActive(Instant issuedAt, Instant expiresAt, Instant revokedAt) {
        if (issuedAt == null || expiresAt == null || revokedAt != null) {
            return false;
        }

        Instant now = clock.instant();
        if (issuedAt.isAfter(now) || !expiresAt.isAfter(now) || !expiresAt.isAfter(issuedAt)) {
            return false;
        }

        try {
            return !expiresAt.isAfter(issuedAt.plus(MAX_SESSION_LIFETIME));
        } catch (ArithmeticException | java.time.DateTimeException invalidTimestamp) {
            return false;
        }
    }
}
