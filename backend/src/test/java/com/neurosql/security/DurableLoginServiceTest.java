package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class DurableLoginServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");
    private static final String EMAIL = "alice@example.test";
    private static final String PASSWORD = "Correct-Horse-Battery-2026";
    private static final UUID USER_ID = UUID.fromString("0db5a89c-0f64-4a88-9c6f-88a8d4a840e7");
    private static final String STORED_HASH = "$2a$10$stored-hash-fixture";

    @Mock
    private JdbcAccountRepository accounts;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private SessionLifecycleService sessions;

    private BasicAuthAttemptRateLimiter rateLimiter;
    private DurableLoginService service;

    @BeforeEach
    void setUp() {
        rateLimiter = new BasicAuthAttemptRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC));
        service = new DurableLoginService(
                accounts,
                passwordEncoder,
                sessions,
                rateLimiter,
                Clock.fixed(NOW, ZoneOffset.UTC),
                "dummy-hash");
    }

    @Test
    void activeVerifiedAccountWithCorrectPasswordReceivesSession() {
        when(accounts.findActiveVerifiedByEmail(EMAIL))
                .thenReturn(Optional.of(new JdbcAccountRepository.LoginAccount(USER_ID, STORED_HASH)));
        when(passwordEncoder.matches(PASSWORD, STORED_HASH)).thenReturn(true);
        SessionLifecycleService.IssuedSession issued =
                new SessionLifecycleService.IssuedSession("A".repeat(43), NOW, NOW.plusSeconds(43200));
        when(sessions.issueSession(USER_ID, NOW)).thenReturn(issued);

        DurableLoginService.LoginAttempt result = service.login(" Alice@Example.test ", PASSWORD);

        assertThat(result.status()).isEqualTo(DurableLoginService.Status.AUTHENTICATED);
        assertThat(result.session()).isEqualTo(issued);
        verify(accounts).findActiveVerifiedByEmail(EMAIL);
        verify(sessions).issueSession(USER_ID, NOW);
    }

    @Test
    void unknownAccountUsesDummyHashAndReturnsGenericFailure() {
        when(accounts.findActiveVerifiedByEmail(EMAIL)).thenReturn(Optional.empty());
        when(passwordEncoder.matches(PASSWORD, "dummy-hash")).thenReturn(false);

        DurableLoginService.LoginAttempt result = service.login(EMAIL, PASSWORD);

        assertThat(result.status()).isEqualTo(DurableLoginService.Status.INVALID_CREDENTIALS);
        assertThat(result.session()).isNull();
        verify(passwordEncoder).matches(PASSWORD, "dummy-hash");
        verify(sessions, never()).issueSession(any(UUID.class), any(Instant.class));
    }

    @Test
    void invalidPasswordStillRunsDummyCandidateCheckAndCannotIssueSession() {
        when(accounts.findActiveVerifiedByEmail(EMAIL))
                .thenReturn(Optional.of(new JdbcAccountRepository.LoginAccount(USER_ID, STORED_HASH)));
        when(passwordEncoder.matches(DurableLoginService.DUMMY_CANDIDATE, STORED_HASH)).thenReturn(false);

        DurableLoginService.LoginAttempt result = service.login(EMAIL, "short");

        assertThat(result.status()).isEqualTo(DurableLoginService.Status.INVALID_CREDENTIALS);
        verify(passwordEncoder).matches(DurableLoginService.DUMMY_CANDIDATE, STORED_HASH);
        verify(sessions, never()).issueSession(any(UUID.class), any(Instant.class));
    }

    @Test
    void repeatedFailuresAreRateLimitedByNormalizedEmail() {
        when(accounts.findActiveVerifiedByEmail(EMAIL)).thenReturn(Optional.empty());
        when(passwordEncoder.matches(anyString(), eq("dummy-hash"))).thenReturn(false);

        for (int i = 0; i < BasicAuthAttemptRateLimiter.MAX_FAILED_ATTEMPTS; i++) {
            assertThat(service.login("Alice@Example.test", PASSWORD).status())
                    .isEqualTo(DurableLoginService.Status.INVALID_CREDENTIALS);
        }

        DurableLoginService.LoginAttempt blocked = service.login(EMAIL, PASSWORD);
        assertThat(blocked.status()).isEqualTo(DurableLoginService.Status.RATE_LIMITED);
        assertThat(blocked.retryAfterSeconds()).isEqualTo(900);
        verify(accounts, times(BasicAuthAttemptRateLimiter.MAX_FAILED_ATTEMPTS))
                .findActiveVerifiedByEmail(EMAIL);
        verify(sessions, never()).issueSession(any(UUID.class), any(Instant.class));
    }

    @Test
    void malformedStoredPasswordHashFailsClosed() {
        when(accounts.findActiveVerifiedByEmail(EMAIL))
                .thenReturn(Optional.of(new JdbcAccountRepository.LoginAccount(USER_ID, "not-a-bcrypt-hash")));
        when(passwordEncoder.matches(PASSWORD, "not-a-bcrypt-hash"))
                .thenThrow(new IllegalArgumentException("malformed hash"));

        DurableLoginService.LoginAttempt result = service.login(EMAIL, PASSWORD);

        assertThat(result.status()).isEqualTo(DurableLoginService.Status.INVALID_CREDENTIALS);
        verify(sessions, never()).issueSession(any(UUID.class), any(Instant.class));
    }
}
