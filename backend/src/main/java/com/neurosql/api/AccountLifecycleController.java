package com.neurosql.api;

import java.util.Objects;
import java.util.function.Supplier;

import com.neurosql.security.TrustedClientAddressResolver;
import com.neurosql.security.VerificationEmailWorkflowService;
import com.neurosql.security.VerificationRequestRateLimiter;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public account-onboarding requests. The controller is absent unless durable
 * auth, shared login throttling, SMTP delivery, and the email feature flag are
 * all configured. Responses never disclose whether an address already exists.
 *
 * <p>The process-local email quota is always applied. An IP quota is added only
 * when operator-controlled trusted proxy CIDRs are configured; without those
 * ranges, using the reverse-proxy peer address could rate-limit every user
 * behind the same edge.</p>
 */
@RestController
@RequestMapping("/api/auth")
@ConditionalOnExpression(
        "'${neurosql.auth.durable-sessions-enabled:false}' == 'true' and "
                + "'${neurosql.auth.shared-rate-limit-enabled:false}' == 'true' and "
                + "'${neurosql.auth.email.enabled:false}' == 'true' and "
                + "'${spring.mail.host:}' != ''")
public class AccountLifecycleController {
    private static final String GENERIC_MESSAGE =
            "If the address can receive verification email, instructions will be sent.";

    private final VerificationEmailWorkflowService emailWorkflow;
    private final VerificationRequestRateLimiter requestRateLimiter;
    private final TrustedClientAddressResolver clientAddressResolver;
    private final boolean trustedProxyRangesConfigured;

    public AccountLifecycleController(
            VerificationEmailWorkflowService emailWorkflow,
            VerificationRequestRateLimiter requestRateLimiter,
            TrustedClientAddressResolver clientAddressResolver,
            @Value("${neurosql.security.trusted-proxy-cidrs:}") String trustedProxyCidrs) {
        this.emailWorkflow = Objects.requireNonNull(emailWorkflow, "emailWorkflow");
        this.requestRateLimiter = Objects.requireNonNull(requestRateLimiter, "requestRateLimiter");
        this.clientAddressResolver = Objects.requireNonNull(clientAddressResolver, "clientAddressResolver");
        this.trustedProxyRangesConfigured = trustedProxyCidrs != null && !trustedProxyCidrs.isBlank();
    }

    @PostMapping(
            path = "/register",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MessageResponse> register(
            @Valid @RequestBody RegistrationRequest request,
            HttpServletRequest servletRequest) {
        return submit(
                VerificationRequestRateLimiter.Purpose.REGISTRATION,
                request.email(),
                servletRequest,
                () -> emailWorkflow.registerAndSend(
                        request.email(), request.password(), request.displayName()));
    }

    @PostMapping(
            path = "/verification/resend",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MessageResponse> resendVerification(
            @Valid @RequestBody EmailRequest request,
            HttpServletRequest servletRequest) {
        return submit(
                VerificationRequestRateLimiter.Purpose.RESEND,
                request.email(),
                servletRequest,
                () -> emailWorkflow.resendVerification(request.email()));
    }

    private ResponseEntity<MessageResponse> submit(
            VerificationRequestRateLimiter.Purpose purpose,
            String email,
            HttpServletRequest servletRequest,
            Supplier<VerificationEmailWorkflowService.RequestAccepted> action) {
        final VerificationRequestRateLimiter.Decision decision;
        try {
            if (trustedProxyRangesConfigured) {
                String clientAddress = clientAddressResolver.resolve(servletRequest);
                if ("unknown".equals(clientAddress)) {
                    return error(HttpStatus.SERVICE_UNAVAILABLE, "Account requests are temporarily unavailable.", null);
                }
                decision = requestRateLimiter.acquire(purpose, email, clientAddress);
            } else {
                decision = requestRateLimiter.acquire(purpose, email);
            }
        } catch (IllegalArgumentException invalidRequest) {
            return error(HttpStatus.BAD_REQUEST, "Account request details are invalid.", null);
        }

        if (!decision.permitted()) {
            return error(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too many requests. Try again later.",
                    decision.retryAfterSeconds());
        }

        try {
            VerificationEmailWorkflowService.RequestAccepted accepted = action.get();
            return accepted(accepted.message());
        } catch (IllegalArgumentException invalidDetails) {
            // Do not expose password-policy internals, account state, or provider details.
            return error(HttpStatus.BAD_REQUEST, "Registration details are invalid.", null);
        }
    }

    private static ResponseEntity<MessageResponse> accepted(String message) {
        return ResponseEntity.accepted()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(new MessageResponse(message));
    }

    private static ResponseEntity<MessageResponse> error(
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

    public record RegistrationRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 256) String password,
            @NotBlank @Size(max = 120) String displayName) {
    }

    public record EmailRequest(@NotBlank @Email @Size(max = 254) String email) {
    }

    public record MessageResponse(String message) {
        public MessageResponse {
            Objects.requireNonNull(message, "message");
        }
    }
}
