package com.neurosql.api;

import java.time.Instant;
import java.util.UUID;

import com.neurosql.security.DurableSessionAuthenticationFilter;
import com.neurosql.security.QueryHistoryReadService;
import com.neurosql.security.WorkspaceAuthorizationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workspace-authorized, user-private query history endpoint.
 *
 * <p>The workspace authorization filter validates membership before this
 * controller runs. This endpoint additionally requires a durable-session
 * principal and always scopes repository reads to that principal's user ID.</p>
 */
@RestController
@RequestMapping("/api/sql")
@ConditionalOnExpression(
        "'${neurosql.auth.durable-sessions-enabled:false}' == 'true' and "
                + "'${neurosql.auth.shared-rate-limit-enabled:false}' == 'true' and "
                + "'${neurosql.sql-history.enabled:false}' == 'true'")
public class QueryHistoryController {
    private final QueryHistoryReadService history;

    public QueryHistoryController(QueryHistoryReadService history) {
        this.history = history;
    }

    @GetMapping("/history")
    public ResponseEntity<?> listHistory(
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(name = "outcome", required = false) String outcome,
            @RequestParam(name = "from", required = false) String fromDate,
            @RequestParam(name = "to", required = false) String toDate,
            @RequestParam(name = "q", required = false) String querySearch,
            Authentication authentication,
            HttpServletRequest request) {
        DurableSessionAuthenticationFilter.SessionPrincipal principal = durablePrincipal(authentication);
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"NeuroSQL\"")
                    .cacheControl(CacheControl.noStore())
                    .body(new ApiError("A durable user session is required."));
        }

        Object workspaceValue = request.getAttribute(WorkspaceAuthorizationFilter.WORKSPACE_REQUEST_ATTRIBUTE);
        if (!(workspaceValue instanceof UUID workspaceId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .cacheControl(CacheControl.noStore())
                    .body(new ApiError("An authorized workspace is required."));
        }

        try {
            QueryHistoryReadService.QueryHistoryPage page =
                    history.list(principal.userId(), workspaceId, limit, cursor, outcome, fromDate, toDate, querySearch);
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .body(page);
        } catch (IllegalArgumentException invalidPage) {
            return ResponseEntity.badRequest()
                    .cacheControl(CacheControl.noStore())
                    .body(new ApiError("The history page request is invalid."));
        }
    }

    private static DurableSessionAuthenticationFilter.SessionPrincipal durablePrincipal(
            Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal()
                        instanceof DurableSessionAuthenticationFilter.SessionPrincipal principal)
                || principal.userId() == null
                || principal.sessionId() == null
                || principal.expiresAt() == null
                || !principal.expiresAt().isAfter(Instant.now())) {
            return null;
        }
        return principal;
    }

    public record ApiError(String message) {
    }
}
