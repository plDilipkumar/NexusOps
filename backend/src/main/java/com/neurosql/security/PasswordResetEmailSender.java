package com.neurosql.security;

/** Trusted out-of-band delivery boundary for password-reset messages. */
public interface PasswordResetEmailSender {
    void sendPasswordResetEmail(
            String recipient,
            AccountActionTokenService.IssuedActionToken token);

    /** Generic delivery failure without provider diagnostics or token material. */
    class DeliveryFailedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public DeliveryFailedException() {
            super("Password-reset email delivery failed.");
        }
    }
}
