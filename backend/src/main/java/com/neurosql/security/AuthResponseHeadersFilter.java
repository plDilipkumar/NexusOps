package com.neurosql.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Prevents browsers and intermediaries from caching authentication API responses,
 * including framework-generated validation and malformed-request errors.
 */
final class AuthResponseHeadersFilter extends OncePerRequestFilter {
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null || path.isBlank()) {
            String uri = request.getRequestURI();
            String contextPath = request.getContextPath();
            if (uri == null) {
                return true;
            }
            path = contextPath != null && !contextPath.isEmpty() && !"/".equals(contextPath)
                    && uri.startsWith(contextPath)
                    ? uri.substring(contextPath.length())
                    : uri;
        }
        return !(path.equals("/api/auth") || path.startsWith("/api/auth/"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        response.setHeader(HttpHeaders.EXPIRES, "0");
        filterChain.doFilter(request, response);
    }
}
