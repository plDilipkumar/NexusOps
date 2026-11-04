package com.neurosql.security;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves opaque bearer sessions for protected metadata, SQL, and auth routes.
 *
 * <p>This filter is registered only when durable sessions are explicitly enabled.
 * It grants only ROLE_USER, never ROLE_OWNER; workspace-specific authorization must
 * still be performed separately before any user data is exposed.</p>
 */
public final class DurableSessionAuthenticationFilter extends OncePerRequestFilter {
    private static final String BEARER_SCHEME = "Bearer";
    private static final String USER_AUTHORITY = "ROLE_USER";

    private final SessionLifecycleService sessions;
    private final Clock clock;

    public DurableSessionAuthenticationFilter(SessionLifecycleService sessions) {
        this(sessions, Clock.systemUTC());
    }

    DurableSessionAuthenticationFilter(SessionLifecycleService sessions, Clock clock) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.clock = Objects.requireNonNull(clock, "clock");
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
        return !(isWithin(path, "/api/schema")
                || isWithin(path, "/api/sql")
                || "/api/auth/login".equals(path)
                || "/api/auth/me".equals(path)
                || "/api/auth/logout".equals(path)
                || "/api/auth/workspaces".equals(path));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (!looksLikeBearerAuthorization(authorization)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (authorization.length() == BEARER_SCHEME.length()
                || authorization.charAt(BEARER_SCHEME.length()) != ' ') {
            rejectBearer(response);
            return;
        }

        String token = authorization.substring(BEARER_SCHEME.length() + 1);
        Instant now = clock.instant();
        Optional<JdbcSessionRepository.StoredSession> activeSession =
                sessions.validateSession(token, now);

        if (activeSession.isEmpty()) {
            SecurityContextHolder.clearContext();
            rejectBearer(response);
            return;
        }

        JdbcSessionRepository.StoredSession session = activeSession.get();
        if (session.userId() == null
                || session.sessionId() == null
                || session.issuedAt() == null
                || session.expiresAt() == null
                || !session.expiresAt().isAfter(now)) {
            SecurityContextHolder.clearContext();
            rejectBearer(response);
            return;
        }

        SessionPrincipal principal = new SessionPrincipal(
                session.userId(), session.sessionId(), session.expiresAt());
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority(USER_AUTHORITY)));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }

    private static boolean looksLikeBearerAuthorization(String authorization) {
        if (authorization == null) {
            return false;
        }
        if (authorization.equalsIgnoreCase(BEARER_SCHEME)) {
            return true;
        }
        return authorization.length() > BEARER_SCHEME.length()
                && authorization.regionMatches(true, 0, BEARER_SCHEME, 0, BEARER_SCHEME.length())
                && Character.isWhitespace(authorization.charAt(BEARER_SCHEME.length()));
    }

    private static boolean isWithin(String path, String basePath) {
        return path != null && (path.equals(basePath) || path.startsWith(basePath + "/"));
    }

    private static void rejectBearer(HttpServletResponse response) {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"NeuroSQL\"");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    /** Contains no bearer secret and is safe to inspect in diagnostic output. */
    public record SessionPrincipal(UUID userId, UUID sessionId, Instant expiresAt) {
    }
}
