package com.neurosql.api;

import java.util.List;
import java.util.UUID;

import com.neurosql.security.DurableSessionAuthenticationFilter;
import com.neurosql.security.QueryHistoryService;
import com.neurosql.security.WorkspaceAuthorizationFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticated query-execution boundary. It is absent unless SQL execution
 * and an existing owner or durable-session authentication mode are enabled.
 */
@RestController
@RequestMapping(path = "/api/sql", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnExpression(
        "'${neurosql.sql-execution.enabled:false}' == 'true' and ("
                + "'${neurosql.auth.enabled:false}' == 'true' or "
                + "('${neurosql.auth.durable-sessions-enabled:false}' == 'true' and "
                + "'${neurosql.auth.shared-rate-limit-enabled:false}' == 'true'))")
public class SqlExecutionController {
    private final ReadOnlyQueryExecutor executor;
    private final ObjectProvider<QueryHistoryService> historyServices;
    private final ActiveQueryRegistry activeQueries;

    public SqlExecutionController(
            ReadOnlyQueryExecutor executor,
            ObjectProvider<QueryHistoryService> historyServices,
            ActiveQueryRegistry activeQueries) {
        this.executor = executor;
        this.historyServices = historyServices;
        this.activeQueries = activeQueries;
    }

    @PostMapping(path = "/execute", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> execute(
            @Valid @RequestBody QueryRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest) {
        long startedAt = System.nanoTime();
        ActiveQueryRegistry.Handle activeQuery = null;
        try {
            activeQuery = beginCancellableExecution(authentication, servletRequest);
            ReadOnlyQueryExecutor.QueryResponse result = activeQuery == null
                    ? executor.execute(request.sql())
                    : executor.execute(request.sql(), activeQuery);
            // Revocation can race with JDBC completion. The handle retains its
            // cancellation flag after executor cleanup, so suppress a result if
            // logout requested cancellation while the statement was active.
            if (activeQuery != null && activeQuery.cancellationRequested()) {
                throw new ReadOnlyQueryExecutor.QueryCancelledException();
            }
            recordSuccessfulExecution(authentication, servletRequest, request.sql(), result);
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(result);
        } catch (InvalidExecutionIdException invalid) {
            return ResponseEntity.badRequest()
                    .cacheControl(CacheControl.noStore())
                    .body(new QueryError("The execution identifier is invalid.", List.of()));
        } catch (CancellationNotAvailableException unavailable) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .cacheControl(CacheControl.noStore())
                    .body(new QueryError("Query cancellation requires a durable workspace session.", List.of()));
        } catch (ActiveQueryRegistry.DuplicateExecutionException duplicate) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .cacheControl(CacheControl.noStore())
                    .body(new QueryError("An execution with this identifier is already active.", List.of()));
        } catch (ActiveQueryRegistry.CapacityExceededException capacity) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .cacheControl(CacheControl.noStore())
                    .body(new QueryError("The server is handling its maximum number of active queries.", List.of()));
        } catch (ReadOnlyQueryExecutor.QueryCancelledException cancelled) {
            recordCanceledExecution(authentication, servletRequest, request.sql(),
                    Math.max(0, (System.nanoTime() - startedAt) / 1_000_000));
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .cacheControl(CacheControl.noStore())
                    .body(new QueryError("The query was cancelled.", List.of()));
        } catch (ReadOnlyQueryExecutor.QueryRejectedException rejected) {
            return ResponseEntity.badRequest()
                    .cacheControl(CacheControl.noStore())
                    .body(new QueryError("The SQL draft was rejected.", rejected.errors()));
        } catch (ReadOnlyQueryExecutor.QueryExecutionUnavailableException unavailable) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .cacheControl(CacheControl.noStore())
                    .body(new QueryError(
                            "SQL execution is not ready. A restricted read-only database role is required.",
                            List.of()));
        } catch (ReadOnlyQueryExecutor.QueryExecutionException failed) {
            recordFailedExecution(authentication, servletRequest, request.sql(),
                    Math.max(0, (System.nanoTime() - startedAt) / 1_000_000));
            return ResponseEntity.unprocessableEntity()
                    .cacheControl(CacheControl.noStore())
                    .body(new QueryError("The query could not be completed.", List.of()));
        } finally {
            if (activeQuery != null) {
                activeQuery.close();
            }
        }
    }

    @PostMapping(path = "/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> cancel(
            @Valid @RequestBody CancelRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest) {
        DurableSessionAuthenticationFilter.SessionPrincipal principal = durablePrincipal(authentication);
        UUID workspaceId = workspaceId(servletRequest);
        if (principal == null || principal.sessionId() == null || workspaceId == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .cacheControl(CacheControl.noStore())
                    .body(new QueryError("Query cancellation requires a durable workspace session.", List.of()));
        }
        return switch (activeQueries.cancel(
                request.executionId(), principal.userId(), principal.sessionId(), workspaceId)) {
            case REQUESTED, ALREADY_REQUESTED -> ResponseEntity.accepted()
                    .cacheControl(CacheControl.noStore())
                    .body(new CancelResponse("CANCEL_REQUESTED"));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .cacheControl(CacheControl.noStore())
                    .body(new QueryError("No active query was found.", List.of()));
            case FAILED -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .cacheControl(CacheControl.noStore())
                    .body(new QueryError("The query could not be cancelled. It may have finished.", List.of()));
        };
    }

    private ActiveQueryRegistry.Handle beginCancellableExecution(
            Authentication authentication,
            HttpServletRequest request) {
        String rawExecutionId = request.getHeader(ActiveQueryRegistry.EXECUTION_ID_HEADER);
        if (rawExecutionId == null) {
            return null;
        }
        DurableSessionAuthenticationFilter.SessionPrincipal principal = durablePrincipal(authentication);
        UUID workspaceId = workspaceId(request);
        if (principal == null || principal.sessionId() == null || workspaceId == null) {
            throw new CancellationNotAvailableException();
        }
        try {
            UUID executionId = UUID.fromString(rawExecutionId);
            if (!executionId.toString().equalsIgnoreCase(rawExecutionId)) {
                throw new IllegalArgumentException("Non-canonical execution identifier.");
            }
            return activeQueries.begin(executionId, principal.userId(), principal.sessionId(), workspaceId);
        } catch (IllegalArgumentException invalid) {
            throw new InvalidExecutionIdException();
        }
    }

    @PostMapping(path = "/explain", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> explain(@Valid @RequestBody QueryRequest request) {
        try {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(executor.explain(request.sql()));
        } catch (ReadOnlyQueryExecutor.QueryRejectedException rejected) {
            return ResponseEntity.badRequest()
                    .cacheControl(CacheControl.noStore())
                    .body(new QueryError("The SQL draft was rejected.", rejected.errors()));
        } catch (ReadOnlyQueryExecutor.QueryExecutionUnavailableException unavailable) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .cacheControl(CacheControl.noStore())
                    .body(new QueryError(
                            "SQL execution is not ready. A restricted read-only database role is required.",
                            List.of()));
        } catch (ReadOnlyQueryExecutor.QueryExecutionException failed) {
            return ResponseEntity.unprocessableEntity()
                    .cacheControl(CacheControl.noStore())
                    .body(new QueryError("The query plan could not be generated.", List.of()));
        }
    }

    private void recordSuccessfulExecution(
            Authentication authentication,
            HttpServletRequest request,
            String sql,
            ReadOnlyQueryExecutor.QueryResponse result) {
        QueryHistoryService history = historyServices.getIfAvailable();
        DurableSessionAuthenticationFilter.SessionPrincipal principal = durablePrincipal(authentication);
        UUID workspaceId = workspaceId(request);
        if (history != null && principal != null && workspaceId != null) {
            history.recordSucceeded(
                    principal.userId(),
                    workspaceId,
                    sql,
                    result.rowCount(),
                    result.executionTimeMillis(),
                    result.truncated());
        }
    }

    private void recordCanceledExecution(
            Authentication authentication,
            HttpServletRequest request,
            String sql,
            long executionTimeMillis) {
        QueryHistoryService history = historyServices.getIfAvailable();
        DurableSessionAuthenticationFilter.SessionPrincipal principal = durablePrincipal(authentication);
        UUID workspaceId = workspaceId(request);
        if (history != null && principal != null && workspaceId != null) {
            history.recordCanceled(principal.userId(), workspaceId, sql, executionTimeMillis);
        }
    }

    private void recordFailedExecution(
            Authentication authentication,
            HttpServletRequest request,
            String sql,
            long executionTimeMillis) {
        QueryHistoryService history = historyServices.getIfAvailable();
        DurableSessionAuthenticationFilter.SessionPrincipal principal = durablePrincipal(authentication);
        UUID workspaceId = workspaceId(request);
        if (history != null && principal != null && workspaceId != null) {
            history.recordFailed(principal.userId(), workspaceId, sql, executionTimeMillis);
        }
    }

    private static DurableSessionAuthenticationFilter.SessionPrincipal durablePrincipal(
            Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        return authentication.getPrincipal()
                instanceof DurableSessionAuthenticationFilter.SessionPrincipal principal
                ? principal
                : null;
    }

    private static UUID workspaceId(HttpServletRequest request) {
        Object value = request.getAttribute(WorkspaceAuthorizationFilter.WORKSPACE_REQUEST_ATTRIBUTE);
        return value instanceof UUID id ? id : null;
    }

    public record QueryRequest(
            @NotBlank(message = "SQL is required.")
            @Size(max = 10_000, message = "SQL must be 10000 characters or fewer.")
            String sql) {}

    public record CancelRequest(@NotNull UUID executionId) {}

    public record CancelResponse(String status) {}

    private static final class InvalidExecutionIdException extends RuntimeException {}

    private static final class CancellationNotAvailableException extends RuntimeException {}

    public record QueryError(String message, List<String> errors) {}
}
