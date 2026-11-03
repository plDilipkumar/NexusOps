package com.neurosql.security;

import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

/**
 * Sends password-reset messages without revealing whether an account exists.
 * Token preparation commits before SMTP delivery so mail latency does not hold
 * the account row lock.
 */
@Service
@ConditionalOnExpression(
        "'${neurosql.auth.durable-sessions-enabled:false}' == 'true' and "
                + "'${neurosql.auth.shared-rate-limit-enabled:false}' == 'true' and "
                + "'${neurosql.auth.email.enabled:false}' == 'true' and "
                + "'${spring.mail.host:}' != '' and "
                + "'${neurosql.auth.email.password-reset-url:}' != ''")
public class PasswordResetEmailWorkflowService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PasswordResetEmailWorkflowService.class);
    private static final String GENERIC_MESSAGE =
            "If an account exists for that address, a password-reset link will be sent.";

    private final PasswordResetRequestPreparationService preparation;
    private final PasswordResetEmailSender emailSender;

    public PasswordResetEmailWorkflowService(
            PasswordResetRequestPreparationService preparation,
            PasswordResetEmailSender emailSender) {
        this.preparation = Objects.requireNonNull(preparation, "preparation");
        this.emailSender = Objects.requireNonNull(emailSender, "emailSender");
    }

    public RequestAccepted requestReset(String email) {
        Optional<PasswordResetRequestPreparationService.PasswordResetDelivery> delivery =
                preparation.prepare(email);
        delivery.ifPresent(this::deliverWithoutLeakingProviderFailure);
        return new RequestAccepted(GENERIC_MESSAGE);
    }

    private void deliverWithoutLeakingProviderFailure(
            PasswordResetRequestPreparationService.PasswordResetDelivery delivery) {
        try {
            emailSender.sendPasswordResetEmail(delivery.recipient(), delivery.token());
        } catch (PasswordResetEmailSender.DeliveryFailedException failure) {
            // Deliberately omit recipient, token, and provider diagnostics.
            LOGGER.warn("Password-reset email delivery failed; the requester may retry.");
        }
    }

    public record RequestAccepted(String message) {
        public RequestAccepted {
            Objects.requireNonNull(message, "message");
        }
    }
}
