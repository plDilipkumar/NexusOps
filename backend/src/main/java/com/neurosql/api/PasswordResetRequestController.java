package com.neurosql.api;

import java.util.Objects;

import com.neurosql.security.PasswordResetEmailWorkflowService;
import com.neurosql.security.SharedAccountRequestRateLimiter;
import com.neurosql.security.TrustedClientAddressResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public password-reset initiation endpoint. It is absent until durable auth,
 * shared PostgreSQL throttling, SMTP, the email feature flag, and a dedicated
 * HTTPS reset URL are all configured.
 */
@RestController
@RequestMapping("/api/auth/password-reset")
@ConditionalOnExpression(
        "'${neurosql.auth.durable-sessions-enabled:false}' == 'true' and "
                + "'${neurosql.auth.shared-rate-limit-enabled:false}' == 'true' and "
                + "'${neurosql.auth.email.enabled:false}' == 'true' and "
                + "'${spring.mail.host:}' != '' and "
                + "'${neurosql.auth.email.verification-url:}' != '' and "
                + "'${neurosql.auth.email.password-reset-url:}' != ''")
public class PasswordResetRequestController {
    private static final String ACCEPTED_MESSAGE =
            "If an account exists for that address, a password-reset link will be sent.";
    private static final String INVALID_REQUEST_MESSAGE = "A valid email address is required.";
    private static final String TEMPORARY_FAILURE_MESSAGE =
            "Password reset is temporarily unavailable. Try again later.";

    private final PasswordResetEmailWorkflowService workflow;
    private final SharedAccountRequestRateLimiter rateLimiter;
    private final TrustedClientAddressResolver clientAddressResolver;
    private final boolean trustedProxyRangesConfigured;

    public PasswordResetRequestController(
            PasswordResetEmailWorkflowService workflow,
            SharedAccountRequestRateLimiter rateLimiter,
            TrustedClientAddressResolver clientAddressResolver,
            @Value("${neurosql.security.trusted-proxy-cidrs:}") String trustedProxyCidrs) {
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.clientAddressResolver = Objects.requireNonNull(clientAddressResolver, "clientAddressResolver");
        this.trustedProxyRangesConfigured = trustedProxyCidrs != null && !trustedProxyCidrs.isBlank();
    }

    @PostMapping(
            path = "/request",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MessageResponse> requestReset(
            @Valid @RequestBody EmailRequest request,
            HttpServletRequest servletRequest) {
        String clientAddress = null;
        if (trustedProxyRangesConfigured) {
            clientAddress = clientAddressResolver.resolve(servletRequest);
            if ("unknown".equals(clientAddress)) {
                return response(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Password-reset requests are temporarily unavailable.",
                        null);
            }
        }

        final SharedAccountRequestRateLimiter.Decision decision;
        try {
            decision = rateLimiter.acquirePasswordReset(request.email(), clientAddress);
        } catch (IllegalArgumentException invalidEmailOrAddress) {
            return response(HttpStatus.BAD_REQUEST, INVALID_REQUEST_MESSAGE, null);
        } catch (RuntimeException limiterUnavailable) {
            return response(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Password-reset requests are temporarily unavailable.",
                    null);
        }

        if (!decision.permitted()) {
            return response(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too many requests. Try again later.",
                    decision.retryAfterSeconds());
        }

        try {
            PasswordResetEmailWorkflowService.RequestAccepted accepted = workflow.requestReset(request.email());
            return response(HttpStatus.ACCEPTED, accepted.message(), null);
        } catch (IllegalArgumentException invalidEmail) {
            return response(HttpStatus.BAD_REQUEST, INVALID_REQUEST_MESSAGE, null);
        } catch (RuntimeException unavailable) {
            return response(HttpStatus.SERVICE_UNAVAILABLE, TEMPORARY_FAILURE_MESSAGE, null);
        }
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<MessageResponse> invalidRequest(MethodArgumentNotValidException ignored) {
        return response(HttpStatus.BAD_REQUEST, INVALID_REQUEST_MESSAGE, null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<MessageResponse> unreadableRequest(HttpMessageNotReadableException ignored) {
        return response(HttpStatus.BAD_REQUEST, INVALID_REQUEST_MESSAGE, null);
    }

    private static ResponseEntity<MessageResponse> response(
            HttpStatus status,
            String message,
            Long retryAfterSeconds) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache");
        if (retryAfterSeconds != null) {
            response.header(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, retryAfterSeconds)));
        }
        return response.body(new MessageResponse(message));
    }

    public record EmailRequest(@NotBlank @Email @Size(max = 254) String email) {
    }

    public record MessageResponse(String message) {
        public MessageResponse {
            Objects.requireNonNull(message, "message");
        }
    }
}
