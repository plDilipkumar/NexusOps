package com.neurosql.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.neurosql.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SqlExecutionController.class)
@AutoConfigureMockMvc
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "neurosql.auth.enabled=true",
        "neurosql.auth.username=sql-test-owner",
        "neurosql.auth.password=Sql-Test-Owner-Password-2026!",
        "neurosql.auth.durable-sessions-enabled=false",
        "neurosql.sql-execution.enabled=true"
})
class SqlExecutionControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReadOnlyQueryExecutor executor;

    @MockitoBean
    private ActiveQueryRegistry activeQueries;

    @Test
    void executionEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/sql/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT 1\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedOwnerReceivesBoundedResultWithNoStoreHeaders() throws Exception {
        var result = new ReadOnlyQueryExecutor.QueryResponse(
                List.of(new ReadOnlyQueryExecutor.ColumnInfo("answer", "int4", java.sql.Types.INTEGER)),
                List.of(List.of("42")),
                1,
                false,
                3);
        when(executor.execute(eq("SELECT 42"))).thenReturn(result);

        mockMvc.perform(post("/api/sql/execute")
                        .with(httpBasic("sql-test-owner", "Sql-Test-Owner-Password-2026!"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT 42\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.rowCount").value(1))
                .andExpect(jsonPath("$.rows[0][0]").value("42"))
                .andExpect(jsonPath("$.truncated").value(false));
    }

    @Test
    void readinessFailureReturnsServiceUnavailableWithoutDatabaseDetails() throws Exception {
        doThrow(new ReadOnlyQueryExecutor.QueryExecutionUnavailableException())
                .when(executor).execute(eq("SELECT 1"));

        mockMvc.perform(post("/api/sql/execute")
                        .with(httpBasic("sql-test-owner", "Sql-Test-Owner-Password-2026!"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT 1\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(
                        "SQL execution is not ready. A restricted read-only database role is required."));
    }

    @Test
    void rejectedSqlReturnsValidationErrorsWithoutExecuting() throws Exception {
        doThrow(new ReadOnlyQueryExecutor.QueryRejectedException(
                List.of("Only SELECT or WITH queries are accepted.")))
                .when(executor).execute(eq("DELETE FROM probe"));

        mockMvc.perform(post("/api/sql/execute")
                        .with(httpBasic("sql-test-owner", "Sql-Test-Owner-Password-2026!"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"DELETE FROM probe\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value(
                        "Only SELECT or WITH queries are accepted."));
    }

    @Test
    void cancellationEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/sql/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"executionId\":\"dc1bd6a0-9fd6-49d9-a65f-b4e8c6b08e1b\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void legacyOwnerCannotCancelDurableWorkspaceQueries() throws Exception {
        mockMvc.perform(post("/api/sql/cancel")
                        .with(httpBasic("sql-test-owner", "Sql-Test-Owner-Password-2026!"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"executionId\":\"dc1bd6a0-9fd6-49d9-a65f-b4e8c6b08e1b\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void explainEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/sql/explain")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT 1\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedOwnerReceivesNonAnalyzingPlanWithNoStoreHeaders() throws Exception {
        when(executor.explain(eq("SELECT 42"))).thenReturn(
                new ReadOnlyQueryExecutor.ExplainResponse(
                        "[{\"Plan\":{\"Node Type\":\"Result\"}}]", 4, false));

        mockMvc.perform(post("/api/sql/explain")
                        .with(httpBasic("sql-test-owner", "Sql-Test-Owner-Password-2026!"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT 42\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.analyze").value(false))
                .andExpect(jsonPath("$.planJson").value(
                        "[{\"Plan\":{\"Node Type\":\"Result\"}}]"));
    }

    @Test
    void databaseFailureIsSanitized() throws Exception {
        doThrow(new ReadOnlyQueryExecutor.QueryExecutionException())
                .when(executor).execute(eq("SELECT 1"));

        mockMvc.perform(post("/api/sql/execute")
                        .with(httpBasic("sql-test-owner", "Sql-Test-Owner-Password-2026!"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT 1\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("The query could not be completed."));
    }
}
