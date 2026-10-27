package com.neurosql.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neurosql.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SqlValidationController.class)
@AutoConfigureMockMvc
@Import({SecurityConfig.class, ReadOnlySqlValidator.class})
@TestPropertySource(properties = {
        "neurosql.auth.enabled=true",
        "neurosql.auth.username=test-owner",
        "neurosql.auth.password=test-password",
        "neurosql.sql-validator.enabled=true"
})
class SqlValidationControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void validationEndpointRequiresOwnerAuthentication() throws Exception {
        mockMvc.perform(post("/api/sql/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT 1\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedOwnerCanValidateReadOnlyDraft() throws Exception {
        mockMvc.perform(post("/api/sql/validate")
                        .with(httpBasic("test-owner", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT 1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.statementType").value("SELECT"))
                .andExpect(jsonPath("$.statementCount").value(1));
    }

    @Test
    void authenticatedOwnerGetsGuardrailForMutation() throws Exception {
        mockMvc.perform(post("/api/sql/validate")
                        .with(httpBasic("test-owner", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"DELETE FROM customers\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));
    }

    @Test
    void rejectsBlankSqlRequest() throws Exception {
        mockMvc.perform(post("/api/sql/validate")
                        .with(httpBasic("test-owner", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }
}
