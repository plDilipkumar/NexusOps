package com.neurosql.security;

import java.net.URI;
import java.util.Objects;
import java.util.regex.Pattern;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Optional SMTP adapter. It is deliberately unavailable until durable auth,
 * shared rate limiting, email delivery, and an SMTP host are all configured.
 *
 * <p>This component is not an HTTP endpoint. Callers must not expose the token
 * or provider exception details to clients or logs.</p>
 */
@Service
@ConditionalOnExpression(
        "'${neurosql.auth.durable-sessions-enabled:false}' == 'true' and "
                + "'${neurosql.auth.shared-rate-limit-enabled:false}' == 'true' and "
                + "'${neurosql.auth.email.enabled:false}' == 'true' and "
                + "'${spring.mail.host:}' != ''")
public class SmtpVerificationEmailSender implements VerificationEmailSender, PasswordResetEmailSender {
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final URI verificationUrl;
    private final URI passwordResetUrl;

    public SmtpVerificationEmailSender(
            JavaMailSender mailSender,
            @Value("${neurosql.auth.email.from:}") String fromAddress,
            @Value("${neurosql.auth.email.verification-url:}") String verificationUrl,
            @Value("${neurosql.auth.email.password-reset-url:}") String passwordResetUrl) {
        this.mailSender = Objects.requireNonNull(mailSender, "mailSender");
        this.fromAddress = validateSenderAddress(fromAddress);
        this.verificationUrl = validateVerificationUrl(verificationUrl);
        this.passwordResetUrl = validateOptionalPasswordResetUrl(passwordResetUrl);
    }

    @Override
    public void sendVerificationEmail(
            String recipient,
            AccountActionTokenService.IssuedActionToken issuedToken) {
        String normalizedRecipient = JdbcAccountRepository.normalizeRegistrationEmail(recipient);
        if (issuedToken == null
                || issuedToken.purpose() != AccountActionTokenService.Purpose.EMAIL_VERIFICATION
                || issuedToken.token() == null
                || !TOKEN_PATTERN.matcher(issuedToken.token()).matches()) {
            throw new IllegalArgumentException("A valid email-verification token is required.");
        }

        String verificationLink = UriComponentsBuilder.fromUri(verificationUrl)
                .queryParam("token", issuedToken.token())
                .build()
                .encode()
                .toUriString();

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(normalizedRecipient);
        message.setSubject("Verify your NeuroSQL email");
        message.setText("""
                Welcome to NeuroSQL.

                Verify your email address using the link below. This link expires in 24 hours:
                %s

                If you did not request this email, you can ignore it.
                """.formatted(verificationLink));

        try {
            mailSender.send(message);
        } catch (MailException deliveryFailure) {
            // Do not propagate provider details or include the one-time token in an error.
            throw new DeliveryFailedException();
        }
    }


    @Override
    public void sendPasswordResetEmail(
            String recipient,
            AccountActionTokenService.IssuedActionToken issuedToken) {
        if (passwordResetUrl == null) {
            throw new IllegalStateException("A password-reset URL must be configured.");
        }
        String normalizedRecipient = JdbcAccountRepository.normalizeRegistrationEmail(recipient);
        if (issuedToken == null
                || issuedToken.purpose() != AccountActionTokenService.Purpose.PASSWORD_RESET
                || issuedToken.token() == null
                || !TOKEN_PATTERN.matcher(issuedToken.token()).matches()) {
            throw new IllegalArgumentException("A valid password-reset token is required.");
        }

        String resetLink = UriComponentsBuilder.fromUri(passwordResetUrl)
                .queryParam("token", issuedToken.token())
                .build()
                .encode()
                .toUriString();

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(normalizedRecipient);
        message.setSubject("Reset your NeuroSQL password");
        message.setText("""
                A password reset was requested for your NeuroSQL account.

                Reset your password using the link below. This link expires in 30 minutes:
                %s

                If you did not request this change, you can ignore this email.
                """.formatted(resetLink));

        try {
            mailSender.send(message);
        } catch (MailException deliveryFailure) {
            throw new PasswordResetEmailSender.DeliveryFailedException();
        }
    }

    private static String validateSenderAddress(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A sender email address must be configured.");
        }
        String candidate = value.strip();
        try {
            InternetAddress address = new InternetAddress(candidate, true);
            address.validate();
            if (!candidate.equals(address.getAddress())) {
                throw new IllegalArgumentException("A sender email address must be configured.");
            }
            return candidate;
        } catch (AddressException invalidAddress) {
            throw new IllegalArgumentException("A valid sender email address must be configured.");
        }
    }

    private static URI validateVerificationUrl(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A verification URL must be configured.");
        }
        return validateHttpsActionUrl(value, "A valid HTTPS verification URL must be configured.");
    }

    private static URI validateOptionalPasswordResetUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return validateHttpsActionUrl(value, "A valid HTTPS password-reset URL must be configured.");
    }

    private static URI validateHttpsActionUrl(String value, String errorMessage) {
        final URI uri;
        try {
            uri = URI.create(value.strip()).normalize();
        } catch (IllegalArgumentException invalidUri) {
            throw new IllegalArgumentException(errorMessage);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException(errorMessage);
        }
        return uri;
    }

    public static final class DeliveryFailedException extends VerificationEmailSender.DeliveryFailedException {
        private static final long serialVersionUID = 1L;
    }
}
