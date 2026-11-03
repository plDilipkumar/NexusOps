package com.neurosql.api;

import java.util.Objects;

import com.neurosql.security.PasswordResetService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
 * Confirms a password reset using the existing one-time PASSWORD_RESET token.
 * The route is absent until durable authentication and shared login throttling
 * are deliberately enabled. SMTP is not required because this endpoint only
 * consumes a token that was issued by the separate request workflow.
 *
 * <p>Raw tokens and submitted passwords are never returned or logged. The
 * underlying service consumes the token, changes the password, and revokes
 * every existing session in one database transaction.</p>
 */
@RestController
@RequestMapping("/api/auth/password-reset")
@ConditionalOnProperty(
        prefix = "neurosql.auth",
        name = {"durable-sessions-enabled", "shared-rate-limit-enabled"},
        havingValue = "true")
public class PasswordResetController {
    private static final String SUCCESS_MESSAGE =
            "Your password has been reset. Please sign in again.";
    private static final String INVALID_TOKEN_MESSAGE =
            "This password-reset link is invalid or has expired. Request a new link.";
    private static final String INVALID_REQUEST_MESSAGE =
            "A valid reset token and new password are required.";
    private static final String PASSWORD_POLICY_MESSAGE =
            "The new password does not meet the security requirements.";
    private static final String TEMPORARY_FAILURE_MESSAGE =
            "Password reset is temporarily unavailable. Try again later.";

    private final PasswordResetService passwordResetService;

    public PasswordResetController(PasswordResetService passwordResetService) {
        this.passwordResetService = Objects.requireNonNull(passwordResetService, "passwordResetService");
    }

    @PostMapping(
            path = "/confirm",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MessageResponse> confirm(@Valid @RequestBody ConfirmationRequest request) {
        try {
            PasswordResetService.ResetResult result =
                    passwordResetService.resetPassword(request.token(), request.newPassword());
            if (result.successful()) {
                return response(HttpStatus.OK, SUCCESS_MESSAGE);
            }
            return response(HttpStatus.BAD_REQUEST, INVALID_TOKEN_MESSAGE);
        } catch (IllegalArgumentException invalidPassword) {
            // PasswordPolicy runs before token consumption; do not expose its details.
            return response(HttpStatus.BAD_REQUEST, PASSWORD_POLICY_MESSAGE);
        } catch (IllegalStateException resetFailure) {
            // Account state and persistence diagnostics must not leak through this public route.
            return response(HttpStatus.SERVICE_UNAVAILABLE, TEMPORARY_FAILURE_MESSAGE);
        }
    }

    /**
     * Spring's default validation resolver logs rejected field values at WARN.
     * Keep both the raw reset token and new password out of application logs.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<MessageResponse> invalidRequest(MethodArgumentNotValidException ignored) {
        return response(HttpStatus.BAD_REQUEST, INVALID_REQUEST_MESSAGE);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<MessageResponse> unreadableRequest(HttpMessageNotReadableException ignored) {
        return response(HttpStatus.BAD_REQUEST, INVALID_REQUEST_MESSAGE);
    }

    private static ResponseEntity<MessageResponse> response(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(new MessageResponse(message));
    }

    public record ConfirmationRequest(
            @NotBlank
            @Size(min = 43, max = 43)
            @Pattern(regexp = "[A-Za-z0-9_-]{43}")
            String token,
            @NotBlank
            @Size(max = 256)
            String newPassword) {
    }

    public record MessageResponse(String message) {
        public MessageResponse {
            Objects.requireNonNull(message, "message");
        }
    }
}
