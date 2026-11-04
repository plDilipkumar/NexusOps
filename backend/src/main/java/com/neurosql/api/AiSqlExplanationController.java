package com.neurosql.api;

import java.util.List;

import com.neurosql.security.DurableSessionAuthenticationFilter;
import com.neurosql.security.SharedAccountRequestRateLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.converter.HttpMessageNotReadableException;

/**
 * Workspace-scoped AI SQL explanation endpoint. It validates and describes a
 * read-only SQL draft but never connects to the query execution data plane.
 */
@RestController
@RequestMapping(path = "/api/sql/ai", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnExpression(
        "'${neurosql.ai.sql-generation.enabled:false}' == 'true' and "
                + "'${neurosql.auth.durable-sessions-enabled:false}' == 'true' and "
                + "'${neurosql.auth.shared-rate-limit-enabled:false}' == 'true'")
public class AiSqlExplanationController {
    private final AiSqlExplanationService service;
    private final SharedAccountRequestRateLimiter rateLimiter;

    public AiSqlExplanationController(
            AiSqlExplanationService service,
            SharedAccountRequestRateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping(path = "/explain", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> explain(
            @RequestBody(required = false) AiSqlExplanationService.ExplainRequest request,
            Authentication authentication) {
        DurableSessionAuthenticationFilter.SessionPrincipal principal = durablePrincipal(authentication);
        if (principal == null) {
            return error(HttpStatus.FORBIDDEN, "AI SQL explanation requires a durable workspace session.");
        }

        final SharedAccountRequestRateLimiter.Decision decision;
        try {
            decision = rateLimiter.acquireAiSqlRequest(principal.userId());
        } catch (DataAccessException | IllegalArgumentException unavailable) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "AI SQL explanation is temporarily unavailable.");
        }
        if (!decision.permitted()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()))
                    .body(new ErrorResponse("AI SQL operations are rate limited.", List.of()));
        }

        try {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(service.explain(request));
        } catch (AiSqlExplanationService.InvalidRequestException invalid) {
            return error(HttpStatus.BAD_REQUEST, "The AI SQL explanation request is invalid.");
        } catch (AiSqlExplanationService.UnsafeQueryException unsafe) {
            return ResponseEntity.unprocessableEntity()
                    .cacheControl(CacheControl.noStore())
                    .body(new ErrorResponse(
                            "The SQL statement did not pass read-only validation.", unsafe.errors()));
        } catch (AiSqlProvider.ProviderUnavailableException unavailable) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "AI SQL explanation is temporarily unavailable.");
        }
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> malformedRequest(HttpMessageNotReadableException ignored) {
        return ResponseEntity.badRequest()
                .cacheControl(CacheControl.noStore())
                .body(new ErrorResponse("The AI SQL explanation request is invalid.", List.of()));
    }

    private static DurableSessionAuthenticationFilter.SessionPrincipal durablePrincipal(
            Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal()
                        instanceof DurableSessionAuthenticationFilter.SessionPrincipal principal)) {
            return null;
        }
        return principal;
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(new ErrorResponse(message, List.of()));
    }

    public record ErrorResponse(String message, List<String> errors) {}
}
