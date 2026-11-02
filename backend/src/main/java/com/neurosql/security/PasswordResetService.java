package com.neurosql.security;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies a password-reset token as one database transaction.
 *
 * <p>The token is consumed, the BCrypt password hash is replaced, and all
 * existing sessions are revoked before the transaction commits. Any failure
 * after token consumption rolls the entire operation back.</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "neurosql.auth",
        name = {"durable-sessions-enabled", "shared-rate-limit-enabled"},
        havingValue = "true")
public class PasswordResetService {
    private final AccountActionTokenService actionTokens;
    private final JdbcAccountRepository accounts;
    private final JdbcSessionRepository sessions;
    private final PasswordEncoder passwordEncoder;

    public PasswordResetService(
            AccountActionTokenService actionTokens,
            JdbcAccountRepository accounts,
            JdbcSessionRepository sessions,
            PasswordEncoder passwordEncoder) {
        this.actionTokens = Objects.requireNonNull(actionTokens, "actionTokens");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder");
    }

    /**
     * Consumes a valid PASSWORD_RESET token and changes the password for an
     * active, email-verified account. Invalid, expired, replayed, and
     * wrong-purpose tokens return the same result.
     *
     * <p>Password policy validation happens before token consumption. The
     * password encoder, account update, and session revocation all execute
     * within this method's transaction.</p>
     */
    @Transactional
    public ResetResult resetPassword(String rawToken, String newPassword) {
        PasswordPolicy.validate(newPassword);

        Optional<UUID> userId = actionTokens.consumeToken(
                rawToken,
                AccountActionTokenService.Purpose.PASSWORD_RESET);
        if (userId.isEmpty()) {
            return ResetResult.invalidToken();
        }

        String encodedPassword = passwordEncoder.encode(newPassword);
        int updated = accounts.updatePasswordHashForActiveVerifiedUser(
                userId.orElseThrow(),
                encodedPassword);
        if (updated != 1) {
            // Throwing is intentional: it rolls token consumption back as well.
            throw new IllegalStateException("The password reset could not be completed.");
        }

        int revokedSessions = sessions.revokeAllForUser(userId.orElseThrow());
        return ResetResult.completed(revokedSessions);
    }

    public record ResetResult(boolean successful, int revokedSessions) {
        public ResetResult {
            if (revokedSessions < 0 || (!successful && revokedSessions != 0)) {
                throw new IllegalArgumentException("The password-reset result is inconsistent.");
            }
        }

        static ResetResult invalidToken() {
            return new ResetResult(false, 0);
        }

        static ResetResult completed(int revokedSessions) {
            return new ResetResult(true, revokedSessions);
        }
    }
}
