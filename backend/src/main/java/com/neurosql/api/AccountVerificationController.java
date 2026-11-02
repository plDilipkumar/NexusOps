package com.neurosql.api;

import java.util.Objects;

import com.neurosql.security.AccountActionTokenService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consumes one-time email-verification links without requiring the SMTP sender
 * to be enabled. The route remains absent until the durable-auth schema and
 * shared login throttling are deliberately enabled.
 *
 * <p>Invalid, expired, and replayed tokens share one response. Raw tokens are
 * never logged or returned to the caller.</p>
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

    private final AccountActionTokenService actionTokens;

    public AccountVerificationController(AccountActionTokenService actionTokens) {
        this.actionTokens = Objects.requireNonNull(actionTokens, "actionTokens");
    }

    @PostMapping(
            path = "/verify-email",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MessageResponse> verifyEmail(
            @Valid @RequestBody TokenRequest request) {
        if (actionTokens.verifyEmail(request.token())) {
            return response(HttpStatus.OK, VERIFIED_MESSAGE);
        }
        return response(HttpStatus.BAD_REQUEST, INVALID_MESSAGE);
    }

    private static ResponseEntity<MessageResponse> response(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(new MessageResponse(message));
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
