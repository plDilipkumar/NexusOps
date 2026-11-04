package com.neurosql.api;

import java.util.List;

import com.neurosql.security.DurableSessionAuthenticationFilter;
import com.neurosql.security.SharedAccountRequestRateLimiter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
 * Workspace-scoped AI performance advisor. It requires the separate read-only
 * SQL data plane to produce a non-ANALYZE plan and never executes the query.
 */
@RestController
@RequestMapping(path = "/api/sql/ai", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnExpression(
        "'${neurosql.ai.sql-generation.enabled:false}' == 'true' and "
                + "'${neurosql.ai.sql-performance.enabled:false}' == 'true' and "
                + "'${neurosql.auth.durable-sessions-enabled:false}' == 'true' and "
                + "'${neurosql.auth.shared-rate-limit-enabled:false}' == 'true' and "
                + "'${neurosql.sql-execution.enabled:false}' == 'true'")
public class AiSqlPerformanceRecommendationController {
    private final AiSqlPerformanceRecommendationService service;
    private final SharedAccountRequestRateLimiter rateLimiter;

    public AiSqlPerformanceRecommendationController(
            AiSqlPerformanceRecommendationService service,
            SharedAccountRequestRateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping(path = "/performance", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> recommend(
            @Valid @RequestBody(required = false) PerformanceRequest request,
            Authentication authentication) {
        DurableSessionAuthenticationFilter.SessionPrincipal principal = durablePrincipal(authentication);
        if (principal == null) {
            return error(HttpStatus.FORBIDDEN, "AI performance recommendations require a durable workspace session.");
        }

        final SharedAccountRequestRateLimiter.Decision decision;
        try {
            decision = rateLimiter.acquireAiSqlRequest(principal.userId());
        } catch (DataAccessException | IllegalArgumentException unavailable) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "AI performance recommendations are temporarily unavailable.");
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
                    .body(service.recommend(request == null
                            ? null
                            : new AiSqlPerformanceRecommendationService.PerformanceRequest(request.sql())));
        } catch (AiSqlPerformanceRecommendationService.InvalidRequestException invalid) {
            return error(HttpStatus.BAD_REQUEST, "The AI performance recommendation request is invalid.");
        } catch (AiSqlPerformanceRecommendationService.UnsafeQueryException unsafe) {
            return ResponseEntity.unprocessableEntity()
                    .cacheControl(CacheControl.noStore())
                    .body(new ErrorResponse(
                            "The SQL statement did not pass read-only validation.", unsafe.errors()));
        } catch (ReadOnlyQueryExecutor.QueryRejectedException rejected) {
            return ResponseEntity.unprocessableEntity()
                    .cacheControl(CacheControl.noStore())
                    .body(new ErrorResponse("The SQL statement did not pass read-only validation.", rejected.errors()));
        } catch (ReadOnlyQueryExecutor.QueryExecutionUnavailableException unavailable) {
            return error(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "AI performance analysis requires a verified restricted read-only database role.");
        } catch (ReadOnlyQueryExecutor.QueryExecutionException failed) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "The query plan could not be generated.");
        } catch (AiSqlProvider.ProviderUnavailableException unavailable) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "AI performance recommendations are temporarily unavailable.");
        }
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> malformedRequest(HttpMessageNotReadableException ignored) {
        return ResponseEntity.badRequest()
                .cacheControl(CacheControl.noStore())
                .body(new ErrorResponse("The AI performance recommendation request is invalid.", List.of()));
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

    public record PerformanceRequest(@NotBlank @Size(max = 10_000) String sql) {}
    public record ErrorResponse(String message, List<String> errors) {}
}
