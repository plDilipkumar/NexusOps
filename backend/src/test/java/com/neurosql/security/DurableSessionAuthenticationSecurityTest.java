package com.neurosql.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.neurosql.api.SchemaController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = SchemaController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "neurosql.auth.enabled=true",
        "neurosql.auth.username=test-owner",
        "neurosql.auth.password=test-owner-password-123",
        "neurosql.auth.durable-sessions-enabled=true",
        "neurosql.schema-browser.enabled=true"
})
class DurableSessionAuthenticationSecurityTest {
    private static final String TOKEN = "A".repeat(43);
    private static final UUID USER_ID = UUID.fromString("b4f41d45-68dc-4f7b-8df0-48e3f29e54c1");
    private static final UUID SESSION_ID = UUID.fromString("4cfa688e-8890-4b5e-8a9a-19f074d5a1f3");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private SessionLifecycleService sessions;

    @MockBean
    private WorkspaceAuthorizationService workspaceAuthorizationService;

    @Test
    void rejectsInvalidBearerBeforeSchemaControllerRuns() throws Exception {
        when(sessions.validateSession(eq(TOKEN), any(Instant.class))).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/schema").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"NeuroSQL\""));

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void validUserSessionMustSupplyWorkspaceBeforeSchemaAccess() throws Exception {
        Instant issuedAt = Instant.now().minusSeconds(60);
        Instant expiresAt = Instant.now().plusSeconds(3600);
        when(sessions.validateSession(eq(TOKEN), any(Instant.class))).thenReturn(Optional.of(
                new JdbcSessionRepository.StoredSession(SESSION_ID, USER_ID, issuedAt, expiresAt)));

        mockMvc.perform(get("/api/schema").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(jdbcTemplate);
        verifyNoInteractions(workspaceAuthorizationService);
    }
}
