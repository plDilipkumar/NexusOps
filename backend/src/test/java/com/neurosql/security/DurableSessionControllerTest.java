package com.neurosql.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.neurosql.api.DurableSessionController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = DurableSessionController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "neurosql.auth.enabled=true",
        "neurosql.auth.username=test-owner",
        "neurosql.auth.password=test-owner-password-123",
        "neurosql.auth.durable-sessions-enabled=true"
})
class DurableSessionControllerTest {
    private static final String TOKEN = "A".repeat(43);
    private static final UUID USER_ID = UUID.fromString("b4f41d45-68dc-4f7b-8df0-48e3f29e54c1");
    private static final UUID SESSION_ID = UUID.fromString("4cfa688e-8890-4b5e-8a9a-19f074d5a1f3");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SessionLifecycleService sessions;

    @Test
    void activeBearerSessionCanLogoutAndReceiveNoContent() throws Exception {
        when(sessions.validateSession(eq(TOKEN), any(Instant.class))).thenReturn(Optional.of(
                new JdbcSessionRepository.StoredSession(
                        SESSION_ID, USER_ID, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600))));
        when(sessions.revokeSession(eq(TOKEN), any(Instant.class))).thenReturn(true);

        mockMvc.perform(post("/api/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));

        verify(sessions).revokeSession(eq(TOKEN), any(Instant.class));
    }

    @Test
    void invalidBearerTokenIsRejectedBeforeLogout() throws Exception {
        when(sessions.validateSession(eq(TOKEN), any(Instant.class))).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"NeuroSQL\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));

        verify(sessions, never()).revokeSession(eq(TOKEN), any(Instant.class));
    }

    @Test
    void missingBearerTokenCannotLogout() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"NeuroSQL\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));

        verifyNoInteractions(sessions);
    }

    @Test
    void basicOwnerCredentialsCannotBeUsedAsDurableSessionLogout() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .with(httpBasic("test-owner", "test-owner-password-123")))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(sessions);
    }

    @Test
    void browserCanPreflightLogoutPost() throws Exception {
        mockMvc.perform(options("/api/auth/logout")
                        .header("Origin", "https://neurosql.vercel.app")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://neurosql.vercel.app"));
    }
}
