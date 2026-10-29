package com.neurosql.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and consumes one-time account-action tokens for future registration,
 * email verification, and password-reset flows.
 *
 * <p>Only the SHA-256 digest is persisted. The raw token is returned once to
 * the internal caller for out-of-band delivery; controllers must never include
 * it in an HTTP response or log it. This service is disabled until the proposed
 * schema and shared limiter are approved and configured.</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "neurosql.auth",
        name = {"durable-sessions-enabled", "shared-rate-limit-enabled"},
        havingValue = "true")
public class AccountActionTokenService {
    private final SessionTokenCodec tokenCodec;
    private final JdbcAccountActionTokenRepository tokens;

    @Autowired
    public AccountActionTokenService(JdbcAccountActionTokenRepository tokens) {
        this(new SessionTokenCodec(), tokens);
    }

    AccountActionTokenService(
            SessionTokenCodec tokenCodec,
            JdbcAccountActionTokenRepository tokens) {
        this.tokenCodec = Objects.requireNonNull(tokenCodec, "tokenCodec");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
    }

    /** Creates a high-entropy token and persists only its digest. */
    public IssuedActionToken issueToken(UUID userId, Purpose purpose) {
        if (userId == null || purpose == null) {
            throw new IllegalArgumentException("A user and account-action purpose are required.");
        }

        String rawToken = tokenCodec.issueToken();
        String tokenHash = tokenCodec.hashToken(rawToken);
        Instant expiresAt = tokens.createToken(
                userId,
                purpose,
                tokenHash,
                purpose.lifetime().getSeconds());
        return new IssuedActionToken(rawToken, purpose, expiresAt);
    }

    /**
     * Consumes a token only if its digest, purpose, and database-side expiry
     * match. An invalid token is indistinguishable from an absent/expired token.
     *
     * <p>Callers that also update account state must wrap both operations in an
     * outer transaction so token consumption and the account update commit or
     * roll back together.</p>
     */
    @Transactional
    public Optional<UUID> consumeToken(String rawToken, Purpose purpose) {
        if (purpose == null) {
            throw new IllegalArgumentException("An account-action purpose is required.");
        }

        final String tokenHash;
        try {
            tokenHash = tokenCodec.hashToken(rawToken);
        } catch (IllegalArgumentException invalidToken) {
            return Optional.empty();
        }
        return tokens.consumeToken(tokenHash, purpose);
    }

    public enum Purpose {
        EMAIL_VERIFICATION(Duration.ofHours(24)),
        PASSWORD_RESET(Duration.ofMinutes(30));

        private final Duration lifetime;

        Purpose(Duration lifetime) {
            this.lifetime = lifetime;
        }

        public Duration lifetime() {
            return lifetime;
        }
    }

    /** The raw token is intentionally redacted from the record's string form. */
    public record IssuedActionToken(String token, Purpose purpose, Instant expiresAt) {
        @Override
        public String toString() {
            return "IssuedActionToken[token=[REDACTED], purpose=" + purpose + ", expiresAt=" + expiresAt + "]";
        }
    }
}
