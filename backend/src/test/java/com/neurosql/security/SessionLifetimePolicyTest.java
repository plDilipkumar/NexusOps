package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SessionLifetimePolicyTest {
    private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");

    @Test
    void setsAFixedTwelveHourExpiryFromIssueTime() {
        SessionLifetimePolicy policy = new SessionLifetimePolicy(Clock.fixed(NOW, ZoneOffset.UTC));
        Instant issuedAt = NOW.minusSeconds(60);

        assertThat(policy.expiresAt(issuedAt))
                .isEqualTo(issuedAt.plus(SessionLifetimePolicy.MAX_SESSION_LIFETIME));
    }

    @Test
    void considersAnUnrevokedUnexpiredSessionActive() {
        SessionLifetimePolicy policy = new SessionLifetimePolicy(Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(policy.isActive(NOW.minusSeconds(3600), NOW.plusSeconds(3600), null)).isTrue();
    }

    @Test
    void rejectsExpiredOrRevokedSessions() {
        SessionLifetimePolicy policy = new SessionLifetimePolicy(Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(policy.isActive(NOW.minusSeconds(7200), NOW, null)).isFalse();
        assertThat(policy.isActive(NOW.minusSeconds(7200), NOW.plusSeconds(3600), NOW.minusSeconds(1))).isFalse();
    }

    @Test
    void rejectsFutureIssueTimesAndExpiryBeyondMaximumLifetime() {
        SessionLifetimePolicy policy = new SessionLifetimePolicy(Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(policy.isActive(NOW.plusSeconds(1), NOW.plusSeconds(3600), null)).isFalse();
        assertThat(policy.isActive(NOW.minusSeconds(3600), NOW.plusSeconds(43200).plusNanos(1), null)).isFalse();
    }

    @Test
    void rejectsInconsistentOrMissingTimestamps() {
        SessionLifetimePolicy policy = new SessionLifetimePolicy(Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(policy.isActive(null, NOW.plusSeconds(3600), null)).isFalse();
        assertThat(policy.isActive(NOW.minusSeconds(3600), null, null)).isFalse();
        assertThat(policy.isActive(NOW, NOW, null)).isFalse();
        assertThat(policy.isActive(NOW, NOW.minusSeconds(1), null)).isFalse();
    }

    @Test
    void rejectsNullIssueTimeWhenCalculatingExpiry() {
        SessionLifetimePolicy policy = new SessionLifetimePolicy(Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> policy.expiresAt(null))
                .isInstanceOf(NullPointerException.class);
    }
}
