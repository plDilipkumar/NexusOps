package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class DurableSessionAuthenticationFilterTest {
    private static final String TOKEN = "A".repeat(43);
    private static final UUID USER_ID = UUID.fromString("b4f41d45-68dc-4f7b-8df0-48e3f29e54c1");
    private static final UUID SESSION_ID = UUID.fromString("4cfa688e-8890-4b5e-8a9a-19f074d5a1f3");
    private static final Instant NOW = Instant.parse("2026-10-28T07:30:00Z");

    @Mock
    private SessionLifecycleService sessions;

    private DurableSessionAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new DurableSessionAuthenticationFilter(sessions, Clock.fixed(NOW, ZoneOffset.UTC));
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void continuesWithoutBearerHeader() throws Exception {
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> continued.set(true));

        assertThat(continued).isTrue();
        verifyNoInteractions(sessions);
    }

    @Test
    void authenticatesActiveBearerAsNonPrivilegedUser() throws Exception {
        JdbcSessionRepository.StoredSession stored = new JdbcSessionRepository.StoredSession(
                SESSION_ID, USER_ID, NOW.minusSeconds(60), NOW.plusSeconds(3600));
        when(sessions.validateSession(TOKEN, NOW)).thenReturn(Optional.of(stored));
        MockHttpServletRequest request = request();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> continued.set(true));

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(continued).isTrue();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getPrincipal()).isEqualTo(
                new DurableSessionAuthenticationFilter.SessionPrincipal(USER_ID, SESSION_ID, stored.expiresAt()));
        assertThat(authentication.getCredentials()).isNull();
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_USER");
        assertThat(authentication.getAuthorities()).extracting("authority").doesNotContain("ROLE_OWNER");
        verify(sessions).validateSession(TOKEN, NOW);
    }

    @Test
    void rejectsUnknownBearerWithoutContinuing() throws Exception {
        when(sessions.validateSession(TOKEN, NOW)).thenReturn(Optional.empty());
        MockHttpServletRequest request = request();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> continued.set(true));

        assertThat(continued).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer realm=\"NeuroSQL\"");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
    }

    @Test
    void rejectsMalformedBearerSchemeWithoutConsultingPersistence() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> continued.set(true));

        assertThat(continued).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(sessions);
    }

    @Test
    void skipsOptionsAndUnprotectedRoutes() throws Exception {
        MockHttpServletRequest optionsRequest = request();
        optionsRequest.setMethod("OPTIONS");
        optionsRequest.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
        MockHttpServletRequest healthRequest = new MockHttpServletRequest();
        healthRequest.setMethod("GET");
        healthRequest.setServletPath("/api/health");
        AtomicBoolean optionsContinued = new AtomicBoolean();
        AtomicBoolean healthContinued = new AtomicBoolean();

        filter.doFilter(optionsRequest, new MockHttpServletResponse(),
                (req, res) -> optionsContinued.set(true));
        filter.doFilter(healthRequest, new MockHttpServletResponse(),
                (req, res) -> healthContinued.set(true));

        assertThat(optionsContinued).isTrue();
        assertThat(healthContinued).isTrue();
        verifyNoInteractions(sessions);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("GET");
        request.setServletPath("/api/schema");
        return request;
    }
}
