package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.neurosql.api.HealthController;
import com.neurosql.api.SchemaController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {HealthController.class, SchemaController.class})
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "neurosql.auth.enabled=true",
        "neurosql.auth.username=test-owner",
        "neurosql.auth.password=test-password",
        "neurosql.schema-browser.enabled=true"
})
class SecurityConfigTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JdbcTemplate jdbcTemplate;

    @Test
    void configuredOwnerPasswordIsStoredAsBcryptHash() {
        PasswordEncoder encoder = new BCryptPasswordEncoder();
        UserDetails owner = new SecurityConfig()
                .ownerUserDetailsService(true, "test-owner", "test-password", encoder)
                .loadUserByUsername("test-owner");

        assertThat(owner.getPassword()).startsWith("$2");
        assertThat(owner.getPassword()).isNotEqualTo("test-password");
        assertThat(encoder.matches("test-password", owner.getPassword())).isTrue();
    }

    @Test
    void weakConfiguredOwnerPasswordIsRejectedBeforeAuthenticationIsEnabled() {
        assertThatThrownBy(() -> new SecurityConfig()
                .ownerUserDetailsService(true, "test-owner", "short", new BCryptPasswordEncoder()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 12 characters");
    }

    @Test
    void schemaMetadataRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/schema"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void configuredOwnerCanReadSchemaMetadata() throws Exception {
        when(jdbcTemplate.queryForObject(eq("SELECT current_database()"), eq(String.class)))
                .thenReturn("neurosql_db");
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of());

        mockMvc.perform(get("/api/schema").with(httpBasic("test-owner", "test-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.database").value("neurosql_db"))
                .andExpect(jsonPath("$.tableCount").value(0));
    }

    @Test
    void healthEndpointRemainsPublic() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void productionFrontendCanPreflightAuthorizationHeader() throws Exception {
        mockMvc.perform(options("/api/schema")
                        .header("Origin", "https://neurosql.vercel.app")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://neurosql.vercel.app"));
    }
}
