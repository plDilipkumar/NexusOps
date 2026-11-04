package com.neurosql.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies the in-memory authentication-failure limiter only to protected NeuroSQL routes.
 * It does not trust forwarded client-IP headers and keys attempts by the Basic username,
 * avoiding accidental global lockout when the service is behind a reverse proxy.
 */
final class BasicAuthRateLimitFilter extends OncePerRequestFilter {
    private static final String BASIC_PREFIX = "Basic ";
    private static final String INVALID_IDENTITY = "__invalid_basic_identity__";
    private static final int MAX_AUTHORIZATION_HEADER_LENGTH = 4096;

    private final BasicAuthAttemptRateLimiter limiter;

    BasicAuthRateLimitFilter(BasicAuthAttemptRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (!isProtectedPath(request) || !isBasicAuthorization(authorization)) {
            filterChain.doFilter(request, response);
            return;
        }

        String identity = extractIdentity(authorization);
        if (limiter.isBlocked(identity)) {
            long retryAfter = Math.max(1, limiter.retryAfterSeconds(identity));
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", Long.toString(retryAfter));
            response.setHeader("Cache-Control", "no-store");
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"error\":\"too_many_authentication_attempts\"}");
            return;
        }

        filterChain.doFilter(request, response);
        if (response.getStatus() == HttpStatus.UNAUTHORIZED.value()) {
            limiter.recordFailure(identity);
        } else {
            // A non-401 response means the Basic credentials were not rejected.
            limiter.recordSuccess(identity);
        }
    }

    private boolean isProtectedPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        return isPathOrChild(uri, "/api/schema") || isPathOrChild(uri, "/api/sql");
    }

    private boolean isPathOrChild(String path, String root) {
        return path.equals(root) || path.startsWith(root + "/");
    }

    private boolean isBasicAuthorization(String authorization) {
        return authorization != null
                && authorization.regionMatches(true, 0, BASIC_PREFIX, 0, BASIC_PREFIX.length());
    }

    private String extractIdentity(String authorization) {
        if (authorization.length() > MAX_AUTHORIZATION_HEADER_LENGTH) {
            return INVALID_IDENTITY;
        }

        byte[] decoded = null;
        try {
            decoded = Base64.getDecoder().decode(authorization.substring(BASIC_PREFIX.length()).trim());
            int separator = -1;
            for (int index = 0; index < decoded.length; index++) {
                if (decoded[index] == (byte) ':') {
                    separator = index;
                    break;
                }
            }
            if (separator <= 0 || separator > 254) {
                return INVALID_IDENTITY;
            }
            String username = new String(decoded, 0, separator, StandardCharsets.ISO_8859_1)
                    .strip()
                    .toLowerCase(Locale.ROOT);
            return username.isEmpty() ? INVALID_IDENTITY : username;
        } catch (IllegalArgumentException malformedBase64) {
            return INVALID_IDENTITY;
        } finally {
            if (decoded != null) {
                Arrays.fill(decoded, (byte) 0);
            }
        }
    }
}
