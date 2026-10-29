package com.neurosql.security;

/**
 * Trusted out-of-band delivery boundary for account-verification messages.
 * Implementations must never log or return the raw one-time token.
 */
public interface VerificationEmailSender {
    void sendVerificationEmail(
            String recipient,
            AccountActionTokenService.IssuedActionToken token);

    /** Generic delivery failure without provider diagnostics or token material. */
    class DeliveryFailedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public DeliveryFailedException() {
            super("Verification email delivery failed.");
        }
    }
}
