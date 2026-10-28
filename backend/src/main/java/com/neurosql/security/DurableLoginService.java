package com.neurosql.security;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Authenticates pre-provisioned, active, email-verified accounts and issues
 * fixed-lifetime opaque sessions. Registration and email verification are
 * deliberately outside this service.
 */
@Service
@ConditionalOnProperty(
        prefix = "neurosql.auth",
        name = "durable-sessions-enabled",
        havingValue = "true")
public class DurableLoginService {
    static final String DUMMY_CANDIDATE = "NeuroSQL login timing dummy password 2026";
    private static final String INVALID_IDENTITY = "__invalid_login_identity__";

    private final JdbcAccountRepository accounts;
    private final PasswordEncoder passwordEncoder;
    private final SessionLifecycleService sessions;
    private final DurableLoginAttemptRateLimiter attemptRateLimiter;
    private final Clock clock;
    private final String dummyPasswordHash;

    @Autowired
    public DurableLoginService(
            JdbcAccountRepository accounts,
            PasswordEncoder passwordEncoder,
            SessionLifecycleService sessions,
            DurableLoginAttemptRateLimiter attemptRateLimiter) {
        this(accounts, passwordEncoder, sessions, attemptRateLimiter,
                Clock.systemUTC(), passwordEncoder.encode(DUMMY_CANDIDATE));
    }

    DurableLoginService(
            JdbcAccountRepository accounts,
            PasswordEncoder passwordEncoder,
            SessionLifecycleService sessions,
            DurableLoginAttemptRateLimiter attemptRateLimiter,
            Clock clock,
            String dummyPasswordHash) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.attemptRateLimiter = Objects.requireNonNull(attemptRateLimiter, "attemptRateLimiter");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (dummyPasswordHash == null || dummyPasswordHash.isBlank()) {
            throw new IllegalArgumentException("A dummy password hash is required.");
        }
        this.dummyPasswordHash = dummyPasswordHash;
    }

    public LoginAttempt login(String email, String password) {
        return login(email, password, clock.instant());
    }

    LoginAttempt login(String email, String password, Instant now) {
        if (now == null) {
            return LoginAttempt.invalidCredentials();
        }

        String normalizedEmail = normalizeEmail(email);
        String rateLimitIdentity = normalizedEmail == null ? INVALID_IDENTITY : normalizedEmail;
        long retryAfter = attemptRateLimiter.retryAfterSeconds(rateLimitIdentity);
        if (retryAfter > 0) {
            return LoginAttempt.rateLimited(retryAfter);
        }

        Optional<JdbcAccountRepository.LoginAccount> account = normalizedEmail == null
                ? Optional.empty()
                : accounts.findActiveVerifiedByEmail(normalizedEmail);

        boolean passwordValid = true;
        String candidatePassword;
        try {
            PasswordPolicy.validate(password);
            candidatePassword = password;
        } catch (IllegalArgumentException invalidPassword) {
            passwordValid = false;
            candidatePassword = DUMMY_CANDIDATE;
        }

        boolean storedCredentialsPresent = account
                .filter(found -> found.userId() != null
                        && found.passwordHash() != null
                        && !found.passwordHash().isBlank())
                .isPresent();
        String storedHash = account
                .map(JdbcAccountRepository.LoginAccount::passwordHash)
                .filter(hash -> hash != null && !hash.isBlank())
                .orElse(dummyPasswordHash);

        boolean passwordMatches;
        try {
            passwordMatches = passwordEncoder.matches(candidatePassword, storedHash);
        } catch (IllegalArgumentException malformedStoredHash) {
            passwordMatches = false;
        }

        if (!passwordValid || !storedCredentialsPresent || !passwordMatches) {
            attemptRateLimiter.recordFailure(rateLimitIdentity);
            return LoginAttempt.invalidCredentials();
        }

        UUID userId = account.orElseThrow().userId();
        attemptRateLimiter.recordSuccess(rateLimitIdentity);
        return LoginAttempt.authenticated(sessions.issueSession(userId, now));
    }

    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank() || email.length() > 254) {
            return null;
        }
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() || normalized.length() > 254 ? null : normalized;
    }

    public enum Status {
        AUTHENTICATED,
        INVALID_CREDENTIALS,
        RATE_LIMITED
    }

    public record LoginAttempt(Status status, SessionLifecycleService.IssuedSession session, long retryAfterSeconds) {
        public static LoginAttempt authenticated(SessionLifecycleService.IssuedSession session) {
            return new LoginAttempt(Status.AUTHENTICATED, Objects.requireNonNull(session, "session"), 0);
        }

        public static LoginAttempt invalidCredentials() {
            return new LoginAttempt(Status.INVALID_CREDENTIALS, null, 0);
        }

        public static LoginAttempt rateLimited(long retryAfterSeconds) {
            return new LoginAttempt(Status.RATE_LIMITED, null, Math.max(1, retryAfterSeconds));
        }
    }
}
