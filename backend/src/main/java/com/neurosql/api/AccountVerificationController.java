package com.neurosql.api;

import java.util.Objects;

import com.neurosql.security.AccountActionTokenService;
import com.neurosql.security.SharedAccountRequestRateLimiter;
import com.neurosql.security.TrustedClientAddressResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * Consumes one-time email-verification links. Token-consumption attempts receive
 * a shared client-IP quota only when the address is resolved through explicitly
 * configured trusted proxy CIDRs. Raw tokens are never logged or returned.
 */
@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(
        prefix = "neurosql.auth",
        name = {"durable-sessions-enabled", "shared-rate-limit-enabled"},
        havingValue = "true")
public class AccountVerificationController {
    private static final String VERIFIED_MESSAGE =
            "Email verified. You can now sign in.";
    private static final String INVALID_MESSAGE =
            "This verification link is invalid or has expired. Request a new verification email.";
    private static final String INVALID_REQUEST_MESSAGE =
            "A valid verification token is required.";
    private static final String TEMPORARY_FAILURE_MESSAGE =
            "Email verification is temporarily unavailable. Try again later.";

    private final AccountActionTokenService actionTokens;
    private final SharedAccountRequestRateLimiter requestRateLimiter;
    private final TrustedClientAddressResolver clientAddressResolver;
    private final boolean trustedProxyRangesConfigured;

    public AccountVerificationController(
            AccountActionTokenService actionTokens,
            SharedAccountRequestRateLimiter requestRateLimiter,
            TrustedClientAddressResolver clientAddressResolver,
            @Value("${neurosql.security.trusted-proxy-cidrs:}") String trustedProxyCidrs) {
        this.actionTokens = Objects.requireNonNull(actionTokens, "actionTokens");
        this.requestRateLimiter = Objects.requireNonNull(requestRateLimiter, "requestRateLimiter");
        this.clientAddressResolver = Objects.requireNonNull(clientAddressResolver, "clientAddressResolver");
        this.trustedProxyRangesConfigured = trustedProxyCidrs != null && !trustedProxyCidrs.isBlank();
    }

    @PostMapping(
            path = "/verify-email",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MessageResponse> verifyEmail(
            @Valid @RequestBody TokenRequest request,
            HttpServletRequest servletRequest) {
        String clientAddress = null;
        if (trustedProxyRangesConfigured) {
            clientAddress = clientAddressResolver.resolve(servletRequest);
            if ("unknown".equals(clientAddress)) {
                return response(HttpStatus.SERVICE_UNAVAILABLE, TEMPORARY_FAILURE_MESSAGE, null);
            }
        }

        final SharedAccountRequestRateLimiter.Decision decision;
        try {
            decision = requestRateLimiter.acquireVerificationConsumption(clientAddress);
        } catch (RuntimeException limiterUnavailable) {
            return response(HttpStatus.SERVICE_UNAVAILABLE, TEMPORARY_FAILURE_MESSAGE, null);
        }

        if (!decision.permitted()) {
            return response(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too many verification attempts. Try again later.",
                    decision.retryAfterSeconds());
        }

        try {
            if (actionTokens.verifyEmail(request.token())) {
                return response(HttpStatus.OK, VERIFIED_MESSAGE, null);
            }
            return response(HttpStatus.BAD_REQUEST, INVALID_MESSAGE, null);
        } catch (RuntimeException persistenceUnavailable) {
            return response(HttpStatus.SERVICE_UNAVAILABLE, TEMPORARY_FAILURE_MESSAGE, null);
        }
    }

    /**
     * Spring's default validation resolver logs rejected field values at WARN.
     * Override it so malformed bearer-like tokens never enter application logs.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<MessageResponse> invalidTokenRequest(
            MethodArgumentNotValidException ignored) {
        return response(HttpStatus.BAD_REQUEST, INVALID_REQUEST_MESSAGE, null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<MessageResponse> unreadableTokenRequest(
            HttpMessageNotReadableException ignored) {
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

    public record TokenRequest(
            @NotBlank
            @Size(min = 43, max = 43)
            @Pattern(regexp = "[A-Za-z0-9_-]{43}")
            String token) {
    }

    public record MessageResponse(String message) {
        public MessageResponse {
            Objects.requireNonNull(message, "message");
        }
    }
}
