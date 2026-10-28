package com.neurosql.security;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Enforces workspace membership for schema and SQL routes when durable sessions
 * are enabled. The legacy configured owner remains privileged; bearer users must
 * name a workspace and hold an active membership.
 */
public final class WorkspaceAuthorizationFilter extends OncePerRequestFilter {
    public static final String WORKSPACE_HEADER = "X-NeuroSQL-Workspace-Id";
    public static final String WORKSPACE_REQUEST_ATTRIBUTE = "neurosql.workspaceId";

    private final WorkspaceAuthorizationService authorization;

    public WorkspaceAuthorizationFilter(WorkspaceAuthorizationService authorization) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getServletPath();
        if (path == null || path.isBlank()) {
            String requestUri = request.getRequestURI();
            String contextPath = request.getContextPath();
            if (requestUri == null) {
                return true;
            }
            path = contextPath != null
                    && !contextPath.isEmpty()
                    && !"/".equals(contextPath)
                    && requestUri.startsWith(contextPath)
                    ? requestUri.substring(contextPath.length())
                    : requestUri;
        }
        return !(isWithin(path, "/api/schema") || isWithin(path, "/api/sql"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // Keep the existing single-owner Basic-auth path compatible. A durable
        // session never receives ROLE_OWNER, so it cannot take this bypass.
        if (isLegacyOwner(authentication)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (authentication == null
                || !(authentication.getPrincipal()
                        instanceof DurableSessionAuthenticationFilter.SessionPrincipal principal)
                || principal.userId() == null) {
            unauthorized(response);
            return;
        }

        String rawWorkspaceId = request.getHeader(WORKSPACE_HEADER);
        if (rawWorkspaceId == null || rawWorkspaceId.isBlank()) {
            reject(response, HttpServletResponse.SC_BAD_REQUEST, "workspace_id_required");
            return;
        }

        UUID workspaceId;
        try {
            workspaceId = UUID.fromString(rawWorkspaceId);
            if (!workspaceId.toString().equalsIgnoreCase(rawWorkspaceId)) {
                reject(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_workspace_id");
                return;
            }
        } catch (IllegalArgumentException invalidId) {
            reject(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_workspace_id");
            return;
        }

        if (!authorization.hasMinimumRole(principal.userId(), workspaceId, WorkspaceRole.VIEWER)) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "workspace_access_denied");
            return;
        }

        request.setAttribute(WORKSPACE_REQUEST_ATTRIBUTE, workspaceId);
        filterChain.doFilter(request, response);
    }

    private static boolean isLegacyOwner(Authentication authentication) {
        if (authentication == null
                || authentication.getPrincipal() instanceof DurableSessionAuthenticationFilter.SessionPrincipal) {
            return false;
        }
        return authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .anyMatch("ROLE_OWNER"::equals);
    }

    private static boolean isWithin(String path, String basePath) {
        return path != null && (path.equals(basePath) || path.startsWith(basePath + "/"));
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"NeuroSQL\"");
        reject(response, HttpServletResponse.SC_UNAUTHORIZED, "authentication_required");
    }

    private static void reject(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + code + "\"}");
    }
}
