package com.neurosql.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

    public SqlExecutionController(ReadOnlyQueryExecutor executor) {
        this.executor = executor;
    }

    @PostMapping(path = "/execute", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> execute(@Valid @RequestBody QueryRequest request) {
        try {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(executor.execute(request.sql()));
        } catch (ReadOnlyQueryExecutor.QueryRejectedException rejected) {
            return ResponseEntity.badRequest()
                    .cacheControl(CacheControl.noStore())
                    .body(new QueryError("The SQL draft was rejected.", rejected.errors()));
        } catch (ReadOnlyQueryExecutor.QueryExecutionUnavailableException unavailable) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .cacheControl(CacheControl.noStore())
                    .body(new QueryError(
                            "SQL execution is not ready. A restricted read-only database role is required.",
                            java.util.List.of()));
        } catch (ReadOnlyQueryExecutor.QueryExecutionException failed) {
            return ResponseEntity.unprocessableEntity()
                    .cacheControl(CacheControl.noStore())
                    .body(new QueryError("The query could not be completed.", java.util.List.of()));
        }
    }

    public record QueryRequest(
            @NotBlank(message = "SQL is required.")
            @Size(max = 10_000, message = "SQL must be 10000 characters or fewer.")
            String sql) {}

    public record QueryError(String message, java.util.List<String> errors) {}
}
