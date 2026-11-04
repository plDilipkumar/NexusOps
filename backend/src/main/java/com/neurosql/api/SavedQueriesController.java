package com.neurosql.api;

import java.time.Instant;
import java.util.UUID;

import com.neurosql.security.DurableSessionAuthenticationFilter;
import com.neurosql.security.SavedQueryService;
import com.neurosql.security.WorkspaceAuthorizationFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Initial saved-query API. Queries are visible to members of the selected
 * workspace; creation is attributed to the authenticated durable-session user.
 */
@RestController
@RequestMapping(path = "/api/sql/saved-queries", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnExpression(
        "'${neurosql.auth.durable-sessions-enabled:false}' == 'true' and "
                + "'${neurosql.auth.shared-rate-limit-enabled:false}' == 'true' and "
                + "'${neurosql.saved-queries.enabled:false}' == 'true'")
public class SavedQueriesController {
    private final SavedQueryService savedQueries;

    public SavedQueriesController(SavedQueryService savedQueries) {
        this.savedQueries = savedQueries;
    }

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String cursor,
            Authentication authentication,
            HttpServletRequest request) {
        DurableSessionAuthenticationFilter.SessionPrincipal principal = durablePrincipal(authentication);
        if (principal == null) {
            return unauthorized();
        }
        UUID workspaceId = workspaceId(request);
        if (workspaceId == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .cacheControl(CacheControl.noStore())
                    .body(new ApiError("An authorized workspace is required."));
        }

        try {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .body(savedQueries.list(workspaceId, limit, q, cursor));
        } catch (IllegalArgumentException invalidPage) {
            return ResponseEntity.badRequest()
                    .cacheControl(CacheControl.noStore())
                    .body(new ApiError("The saved-query page request is invalid."));
        }
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> create(
            @Valid @RequestBody SaveQueryRequest requestBody,
            Authentication authentication,
            HttpServletRequest request) {
        DurableSessionAuthenticationFilter.SessionPrincipal principal = durablePrincipal(authentication);
        if (principal == null) {
            return unauthorized();
        }
        UUID workspaceId = workspaceId(request);
        if (workspaceId == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .cacheControl(CacheControl.noStore())
                    .body(new ApiError("An authorized workspace is required."));
        }

        try {
            JdbcSavedQueryResponse response = new JdbcSavedQueryResponse(savedQueries.create(
                    workspaceId,
                    principal.userId(),
                    requestBody.name(),
                    requestBody.description(),
                    requestBody.queryText()));
            return ResponseEntity.status(HttpStatus.CREATED)
                    .cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .body(response);
        } catch (SavedQueryService.DuplicateNameException duplicate) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .cacheControl(CacheControl.noStore())
                    .body(new ApiError("A saved query with this name already exists in the workspace."));
        } catch (IllegalArgumentException invalidInput) {
            return ResponseEntity.badRequest()
                    .cacheControl(CacheControl.noStore())
                    .body(new ApiError("The saved-query details are invalid."));
        }
    }

    @PutMapping(path = "/{savedQueryId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> update(
            @PathVariable UUID savedQueryId,
            @Valid @RequestBody SaveQueryRequest requestBody,
            Authentication authentication,
            HttpServletRequest request) {
        DurableSessionAuthenticationFilter.SessionPrincipal principal = durablePrincipal(authentication);
        if (principal == null) {
            return unauthorized();
        }
        UUID workspaceId = workspaceId(request);
        if (workspaceId == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .cacheControl(CacheControl.noStore())
                    .body(new ApiError("An authorized workspace is required."));
        }

        try {
            var updated = savedQueries.update(
                    workspaceId,
                    savedQueryId,
                    requestBody.name(),
                    requestBody.description(),
                    requestBody.queryText());
            if (updated.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .cacheControl(CacheControl.noStore())
                        .body(new ApiError("Saved query not found."));
            }
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .body(new JdbcSavedQueryResponse(updated.get()));
        } catch (SavedQueryService.DuplicateNameException duplicate) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .cacheControl(CacheControl.noStore())
                    .body(new ApiError("A saved query with this name already exists in the workspace."));
        } catch (IllegalArgumentException invalidInput) {
            return ResponseEntity.badRequest()
                    .cacheControl(CacheControl.noStore())
                    .body(new ApiError("The saved-query details are invalid."));
        }
    }

    @DeleteMapping("/{savedQueryId}")
    public ResponseEntity<?> delete(
            @PathVariable UUID savedQueryId,
            Authentication authentication,
            HttpServletRequest request) {
        DurableSessionAuthenticationFilter.SessionPrincipal principal = durablePrincipal(authentication);
        if (principal == null) {
            return unauthorized();
        }
        UUID workspaceId = workspaceId(request);
        if (workspaceId == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .cacheControl(CacheControl.noStore())
                    .body(new ApiError("An authorized workspace is required."));
        }

        if (!savedQueries.delete(workspaceId, savedQueryId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .cacheControl(CacheControl.noStore())
                    .body(new ApiError("Saved query not found."));
        }
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .build();
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

    private static UUID workspaceId(HttpServletRequest request) {
        Object value = request.getAttribute(WorkspaceAuthorizationFilter.WORKSPACE_REQUEST_ATTRIBUTE);
        return value instanceof UUID id ? id : null;
    }

    private static ResponseEntity<ApiError> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"NeuroSQL\"")
                .cacheControl(CacheControl.noStore())
                .body(new ApiError("A durable user session is required."));
    }

    public record SaveQueryRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description,
            @NotBlank @Size(max = 50_000) String queryText) {
    }

    public record JdbcSavedQueryResponse(
            UUID savedQueryId,
            UUID workspaceId,
            UUID ownerUserId,
            String name,
            String description,
            String queryText,
            Instant createdAt,
            Instant updatedAt) {
        JdbcSavedQueryResponse(com.neurosql.security.JdbcSavedQueryRepository.SavedQueryEntry entry) {
            this(
                    entry.savedQueryId(),
                    entry.workspaceId(),
                    entry.ownerUserId(),
                    entry.name(),
                    entry.description(),
                    entry.queryText(),
                    entry.createdAt(),
                    entry.updatedAt());
        }
    }

    public record ApiError(String message) {
    }
}
