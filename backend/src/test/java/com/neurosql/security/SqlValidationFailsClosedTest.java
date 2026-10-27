package com.neurosql.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neurosql.api.ReadOnlySqlValidator;
import com.neurosql.api.SqlValidationController;
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
        "neurosql.auth.enabled=false",
        "neurosql.sql-validator.enabled=true"
})
class SqlValidationFailsClosedTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void sqlValidationRemainsForbiddenWhenAuthenticationIsDisabled() throws Exception {
        mockMvc.perform(post("/api/sql/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT 1\"}"))
                .andExpect(status().isForbidden());
    }
}
