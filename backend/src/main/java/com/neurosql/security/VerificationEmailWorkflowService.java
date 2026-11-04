package com.neurosql.security;

import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

/**
 * Connects account registration/resend requests to the configured email sender.
 * Public callers receive the same response for new, existing, absent, and
 * temporarily undeliverable addresses. This is a service boundary, not a controller.
 */
@Service
@ConditionalOnExpression(
        "'${neurosql.auth.durable-sessions-enabled:false}' == 'true' and "
                + "'${neurosql.auth.shared-rate-limit-enabled:false}' == 'true' and "
                + "'${neurosql.auth.email.enabled:false}' == 'true' and "
                + "'${spring.mail.host:}' != ''")
public class VerificationEmailWorkflowService {
    private static final Logger LOGGER = LoggerFactory.getLogger(VerificationEmailWorkflowService.class);
    private static final String GENERIC_MESSAGE =
            "If the address can receive verification email, instructions will be sent.";

    private final RegistrationService registration;
    private final VerificationResendPreparationService resendPreparation;
    private final VerificationEmailSender emailSender;

    public VerificationEmailWorkflowService(
            RegistrationService registration,
            VerificationResendPreparationService resendPreparation,
            VerificationEmailSender emailSender) {
        this.registration = Objects.requireNonNull(registration, "registration");
        this.resendPreparation = Objects.requireNonNull(resendPreparation, "resendPreparation");
        this.emailSender = Objects.requireNonNull(emailSender, "emailSender");
    }

    public RequestAccepted registerAndSend(String email, String password, String displayName) {
        String normalizedEmail = JdbcAccountRepository.normalizeRegistrationEmail(email);
        RegistrationService.RegistrationAttempt attempt = registration.register(email, password, displayName);
        attempt.verificationDelivery().ifPresent(token -> deliverWithoutLeakingProviderFailure(normalizedEmail, token));
        return accepted();
    }

    public RequestAccepted resendVerification(String email) {
        Optional<VerificationResendPreparationService.VerificationDelivery> delivery =
                resendPreparation.prepare(email);
        delivery.ifPresent(value -> deliverWithoutLeakingProviderFailure(value.recipient(), value.token()));
        return accepted();
    }

    private void deliverWithoutLeakingProviderFailure(
            String recipient,
            AccountActionTokenService.IssuedActionToken token) {
        try {
            emailSender.sendVerificationEmail(recipient, token);
        } catch (VerificationEmailSender.DeliveryFailedException failure) {
            // Deliberately avoid recipient, token, and provider details in logs.
            LOGGER.warn("Verification email delivery failed; the requester may retry.");
        }
    }

    private static RequestAccepted accepted() {
        return new RequestAccepted(GENERIC_MESSAGE);
    }

    public record RequestAccepted(String message) {
        public RequestAccepted {
            Objects.requireNonNull(message, "message");
        }
    }
}
