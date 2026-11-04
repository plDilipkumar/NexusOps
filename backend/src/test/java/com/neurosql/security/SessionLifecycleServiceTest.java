package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SessionLifecycleServiceTest {
    private static final UUID USER_ID = UUID.fromString("b4f41d45-68dc-4f7b-8df0-48e3f29e54c1");
    private static final Instant ISSUED_AT = Instant.parse("2026-10-28T08:00:00Z");
    private static final Instant NOW = ISSUED_AT.plusSeconds(60);

    @Mock
    private JdbcSessionRepository sessions;

    private SessionTokenCodec tokenCodec;
    private SessionLifecycleService service;

    @BeforeEach
    void setUp() {
        tokenCodec = new SessionTokenCodec();
        service = new SessionLifecycleService(tokenCodec, new SessionLifetimePolicy(), sessions);
    }

    @Test
    void issuesTokenButPersistsOnlyItsDigestAndRedactsToString() {
        SessionLifecycleService.IssuedSession issued = service.issueSession(USER_ID, ISSUED_AT);
        String expectedHash = tokenCodec.hashToken(issued.token());

        assertThat(issued.token()).matches("[A-Za-z0-9_-]{43}");
        assertThat(issued.issuedAt()).isEqualTo(ISSUED_AT);
        assertThat(issued.expiresAt()).isEqualTo(ISSUED_AT.plusSeconds(12 * 60 * 60));
        assertThat(issued.toString()).doesNotContain(issued.token()).contains("[REDACTED]");
        verify(sessions).createSession(USER_ID, expectedHash, ISSUED_AT, issued.expiresAt());
    }

    @Test
    void rejectsMissingIdentityOrUnrepresentableIssueTimeBeforePersistence() {
        assertThatThrownBy(() -> service.issueSession(null, ISSUED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.issueSession(USER_ID, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.issueSession(USER_ID, Instant.MAX))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(sessions);
    }

    @Test
    void validatesOnlyCanonicalTokensThroughTheirDigest() {
        String token = tokenCodec.issueToken();
        String tokenHash = tokenCodec.hashToken(token);
        JdbcSessionRepository.StoredSession expected = new JdbcSessionRepository.StoredSession(
                UUID.fromString("4cfa688e-8890-4b5e-8a9a-19f074d5a1f3"),
                USER_ID,
                ISSUED_AT,
                ISSUED_AT.plusSeconds(3600));
        when(sessions.findActiveByTokenHash(tokenHash, NOW)).thenReturn(Optional.of(expected));

        assertThat(service.validateSession(token, NOW)).contains(expected);
        assertThat(service.validateSession("not-a-token", NOW)).isEmpty();
        assertThat(service.validateSession(token, null)).isEmpty();

        verify(sessions).findActiveByTokenHash(tokenHash, NOW);
        verify(sessions, never()).findActiveByTokenHash("not-a-token", NOW);
    }

    @Test
    void revokesByDigestAndFailsClosedForMalformedTokensOrMissingTime() {
        String token = tokenCodec.issueToken();
        String tokenHash = tokenCodec.hashToken(token);
        when(sessions.revokeByTokenHash(tokenHash, NOW)).thenReturn(true);

        assertThat(service.revokeSession(token, NOW)).isTrue();
        assertThat(service.revokeSession("not-a-token", NOW)).isFalse();
        assertThat(service.revokeSession(token, null)).isFalse();

        verify(sessions).revokeByTokenHash(tokenHash, NOW);
        verify(sessions, never()).revokeByTokenHash("not-a-token", NOW);
    }
}
