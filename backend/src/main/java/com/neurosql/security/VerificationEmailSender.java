package com.neurosql.security;

/**
 * Trusted out-of-band delivery boundary for account-verification messages.
 * Implementations must never log or return the raw one-time token.
 */
public interface VerificationEmailSender {
    void sendVerificationEmail(
            String recipient,
            AccountActionTokenService.IssuedActionToken token);
}
