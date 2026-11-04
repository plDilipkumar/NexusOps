package com.neurosql.security;

import java.util.Objects;
import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rotates verification tokens for active, unverified accounts. The account row
 * is locked while old tokens are consumed and the replacement is inserted.
 * Email delivery is intentionally performed by another bean after this
 * transaction commits, so SMTP latency never holds database locks.
 */
@Service
@ConditionalOnExpression(
        "'${neurosql.auth.durable-sessions-enabled:false}' == 'true' and "
                + "'${neurosql.auth.shared-rate-limit-enabled:false}' == 'true' and "
                + "'${neurosql.auth.email.enabled:false}' == 'true' and "
                + "'${spring.mail.host:}' != ''")
public class VerificationResendPreparationService {
    private final JdbcAccountRepository accounts;
    private final AccountActionTokenService actionTokens;

    public VerificationResendPreparationService(
            JdbcAccountRepository accounts,
            AccountActionTokenService actionTokens) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.actionTokens = Objects.requireNonNull(actionTokens, "actionTokens");
    }

    @Transactional
    public Optional<VerificationDelivery> prepare(String email) {
        final String normalizedEmail;
        try {
            normalizedEmail = JdbcAccountRepository.normalizeRegistrationEmail(email);
        } catch (IllegalArgumentException invalidEmail) {
            return Optional.empty();
        }

        return accounts.findActiveUnverifiedByEmailForUpdate(normalizedEmail)
                .map(account -> new VerificationDelivery(
                        account.email(),
                        actionTokens.replaceOutstandingToken(
                                account.userId(), AccountActionTokenService.Purpose.EMAIL_VERIFICATION)));
    }

    public record VerificationDelivery(
            String recipient,
            AccountActionTokenService.IssuedActionToken token) {
        public VerificationDelivery {
            Objects.requireNonNull(recipient, "recipient");
            Objects.requireNonNull(token, "token");
        }

        @Override
        public String toString() {
            return "VerificationDelivery[recipient=[REDACTED], token=[REDACTED]]";
        }
    }
}
