package com.neurosql.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.neurosql.api.SchemaController;
import com.neurosql.api.WorkspaceController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;

@WebMvcTest(controllers = {SchemaController.class, WorkspaceController.class})
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "neurosql.auth.enabled=true",
        "neurosql.auth.username=test-owner",
        "neurosql.auth.password=test-owner-password-123",
        "neurosql.auth.durable-sessions-enabled=true",
        "neurosql.schema-browser.enabled=true"
})
class WorkspaceAuthorizationHttpTest {
    private static final String TOKEN = "A".repeat(43);
    private static final UUID USER_ID = UUID.fromString("b4f41d45-68dc-4f7b-8df0-48e3f29e54c1");
    private static final UUID SESSION_ID = UUID.fromString("4cfa688e-8890-4b5e-8a9a-19f074d5a1f3");
    private static final UUID WORKSPACE_ID = UUID.fromString("b66c9e57-9e08-4b86-8f3f-65e8d9c60d6b");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private SessionLifecycleService sessions;

    @MockBean
    private WorkspaceAuthorizationService workspaceAuthorizationService;

    @Test
    void durableSessionMustProvideCanonicalWorkspaceId() throws Exception {
        authenticateSession();

        mockMvc.perform(get("/api/schema")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("workspace_id_required"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));

        verifyNoInteractions(workspaceAuthorizationService);
    }

    @Test
    void activeViewerCanReadSchemaOnlyForAuthorizedWorkspace() throws Exception {
        authenticateSession();
        mockSchemaDatabase();
        when(workspaceAuthorizationService.hasMinimumRole(USER_ID, WORKSPACE_ID, WorkspaceRole.VIEWER))
                .thenReturn(true);

        mockMvc.perform(get("/api/schema")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .header(WorkspaceAuthorizationFilter.WORKSPACE_HEADER, WORKSPACE_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.database").value("neurosql_db"))
                .andExpect(jsonPath("$.tableCount").value(0));

        verify(workspaceAuthorizationService)
                .hasMinimumRole(USER_ID, WORKSPACE_ID, WorkspaceRole.VIEWER);
    }

    @Test
    void missingMembershipIsDeniedBeforeSchemaMetadataIsRead() throws Exception {
        authenticateSession();
        when(workspaceAuthorizationService.hasMinimumRole(USER_ID, WORKSPACE_ID, WorkspaceRole.VIEWER))
                .thenReturn(false);

        mockMvc.perform(get("/api/schema")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .header(WorkspaceAuthorizationFilter.WORKSPACE_HEADER, WORKSPACE_ID.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("workspace_access_denied"));

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void legacyConfiguredOwnerRemainsCompatibleWithoutWorkspaceHeader() throws Exception {
        mockSchemaDatabase();

        mockMvc.perform(get("/api/schema").with(httpBasic("test-owner", "test-owner-password-123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.database").value("neurosql_db"));

        verifyNoInteractions(workspaceAuthorizationService);
    }

    @Test
    void authenticatedUserCanDiscoverOnlyTheirActiveWorkspaces() throws Exception {
        authenticateSession();
        when(workspaceAuthorizationService.listActiveWorkspaces(USER_ID)).thenReturn(List.of(
                new JdbcWorkspaceAuthorizationRepository.ActiveWorkspace(
                        WORKSPACE_ID, "Engineering", "engineering", WorkspaceRole.MEMBER)));

        mockMvc.perform(get("/api/auth/workspaces")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$[0].workspaceId").value(WORKSPACE_ID.toString()))
                .andExpect(jsonPath("$[0].name").value("Engineering"))
                .andExpect(jsonPath("$[0].slug").value("engineering"))
                .andExpect(jsonPath("$[0].role").value("MEMBER"))
                .andExpect(jsonPath("$[0].token").doesNotExist());
    }

    @Test
    void basicOwnerCannotUseDurableWorkspaceDiscovery() throws Exception {
        mockMvc.perform(get("/api/auth/workspaces")
                        .with(httpBasic("test-owner", "test-owner-password-123")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void workspaceHeaderIsAllowedByProductionCorsPreflight() throws Exception {
        mockMvc.perform(options("/api/schema")
                        .header("Origin", "https://neurosql.vercel.app")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers",
                                "authorization,x-neurosql-workspace-id"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Access-Control-Allow-Origin", "https://neurosql.vercel.app"));
    }

    private void authenticateSession() {
        when(sessions.validateSession(eq(TOKEN), any(Instant.class))).thenReturn(Optional.of(
                new JdbcSessionRepository.StoredSession(
                        SESSION_ID, USER_ID, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600))));
    }

    private void mockSchemaDatabase() {
        when(jdbcTemplate.queryForObject(eq("SELECT current_database()"), eq(String.class)))
                .thenReturn("neurosql_db");
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of());
    }
}
