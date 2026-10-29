package com.neurosql.api;

import java.util.List;
import java.util.UUID;

import com.neurosql.security.DurableSessionAuthenticationFilter;
import com.neurosql.security.JdbcWorkspaceAuthorizationRepository;
import com.neurosql.security.WorkspaceAuthorizationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Workspace discovery for authenticated durable-session users. */
@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(
        prefix = "neurosql.auth",
        name = "durable-sessions-enabled",
        havingValue = "true")
public class WorkspaceController {
    private final WorkspaceAuthorizationService workspaces;

    public WorkspaceController(WorkspaceAuthorizationService workspaces) {
        this.workspaces = workspaces;
    }

    @GetMapping("/workspaces")
    public ResponseEntity<?> listWorkspaces(Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal()
                        instanceof DurableSessionAuthenticationFilter.SessionPrincipal principal)
                || principal.userId() == null
                || principal.sessionId() == null
                || principal.expiresAt() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"NeuroSQL\"")
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .build();
        }

        List<WorkspaceResponse> response = workspaces.listActiveWorkspaces(principal.userId()).stream()
                .map(WorkspaceResponse::from)
                .toList();
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(response);
    }

    public record WorkspaceResponse(UUID workspaceId, String name, String slug, String role) {
        static WorkspaceResponse from(JdbcWorkspaceAuthorizationRepository.ActiveWorkspace workspace) {
            return new WorkspaceResponse(
                    workspace.workspaceId(), workspace.name(), workspace.slug(), workspace.role().name());
        }
    }
}
