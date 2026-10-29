package com.neurosql.security;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Coordinates unverified account creation and email-verification token issuance.
 *
 * <p>The account row and token digest must commit together. If token issuance
 * fails, the surrounding transaction rolls the new account back. This service
 * is not an HTTP controller and must only be called by a trusted delivery
 * boundary; raw tokens must never be returned in an HTTP response or logged.</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "neurosql.auth",
        name = {"durable-sessions-enabled", "shared-rate-limit-enabled"},
        havingValue = "true")
public class RegistrationService {
    private final JdbcAccountRepository accounts;
    private final PasswordEncoder passwordEncoder;
    private final AccountActionTokenService actionTokens;

    public RegistrationService(
            JdbcAccountRepository accounts,
            PasswordEncoder passwordEncoder,
            AccountActionTokenService actionTokens) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder");
        this.actionTokens = Objects.requireNonNull(actionTokens, "actionTokens");
    }

    /**
     * Creates an unverified account and its verification token atomically.
     *
     * <p>An empty delivery token means the normalized email already existed.
     * A future public endpoint must return the same generic response for both
     * outcomes and must not serialize this result.</p>
     */
    @Transactional
    public RegistrationAttempt register(String email, String password, String displayName) {
        String normalizedEmail = JdbcAccountRepository.normalizeRegistrationEmail(email);
        String normalizedDisplayName = JdbcAccountRepository.normalizeDisplayName(displayName);
        PasswordPolicy.validate(password);

        // Hash before checking uniqueness so duplicate emails still incur password hashing.
        String passwordHash = passwordEncoder.encode(password);
        Optional<UUID> userId = accounts.createUnverifiedAccount(
                normalizedEmail, passwordHash, normalizedDisplayName);

        if (userId.isEmpty()) {
            return RegistrationAttempt.noDelivery();
        }

        AccountActionTokenService.IssuedActionToken token = actionTokens.issueToken(
                userId.orElseThrow(), AccountActionTokenService.Purpose.EMAIL_VERIFICATION);
        return RegistrationAttempt.deliveryPending(token);
    }

    /**
     * Internal result for the future mail-delivery adapter. It is deliberately
     * not an HTTP response DTO and redacts the token in its string form.
     */
    public record RegistrationAttempt(
            Optional<AccountActionTokenService.IssuedActionToken> verificationDelivery) {
        public RegistrationAttempt {
            verificationDelivery = Objects.requireNonNull(
                    verificationDelivery, "verificationDelivery");
        }

        static RegistrationAttempt noDelivery() {
            return new RegistrationAttempt(Optional.empty());
        }

        static RegistrationAttempt deliveryPending(
                AccountActionTokenService.IssuedActionToken token) {
            return new RegistrationAttempt(Optional.of(
                    Objects.requireNonNull(token, "token")));
        }

        @Override
        public String toString() {
            return "RegistrationAttempt[verificationDelivery="
                    + (verificationDelivery.isPresent() ? "[REDACTED]" : "empty") + "]";
        }
    }
}
