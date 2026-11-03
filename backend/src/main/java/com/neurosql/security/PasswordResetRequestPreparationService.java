package com.neurosql.security;

import java.util.Objects;
import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Locks an active, verified account while replacing its outstanding password-reset token.
 * Delivery is performed by a separate service after this transaction commits.
 */
@Service
@ConditionalOnProperty(
        prefix = "neurosql.auth",
        name = {"durable-sessions-enabled", "shared-rate-limit-enabled"},
        havingValue = "true")
public class PasswordResetRequestPreparationService {
    private final JdbcAccountRepository accounts;
    private final AccountActionTokenService actionTokens;

    public PasswordResetRequestPreparationService(
            JdbcAccountRepository accounts,
            AccountActionTokenService actionTokens) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.actionTokens = Objects.requireNonNull(actionTokens, "actionTokens");
    }

    @Transactional
    public Optional<PasswordResetDelivery> prepare(String email) {
        final String normalizedEmail;
        try {
            normalizedEmail = JdbcAccountRepository.normalizeRegistrationEmail(email);
        } catch (IllegalArgumentException invalidEmail) {
            return Optional.empty();
        }

        return accounts.findActiveVerifiedByEmailForUpdate(normalizedEmail)
                .map(account -> new PasswordResetDelivery(
                        normalizedEmail,
                        actionTokens.replaceOutstandingToken(
                                account.userId(), AccountActionTokenService.Purpose.PASSWORD_RESET)));
    }

    public record PasswordResetDelivery(
            String recipient,
            AccountActionTokenService.IssuedActionToken token) {
        public PasswordResetDelivery {
            Objects.requireNonNull(recipient, "recipient");
            Objects.requireNonNull(token, "token");
            if (token.purpose() != AccountActionTokenService.Purpose.PASSWORD_RESET) {
                throw new IllegalArgumentException("A password-reset token is required.");
            }
        }

        @Override
        public String toString() {
            return "PasswordResetDelivery[recipient=[REDACTED], token=[REDACTED]]";
        }
    }
}
