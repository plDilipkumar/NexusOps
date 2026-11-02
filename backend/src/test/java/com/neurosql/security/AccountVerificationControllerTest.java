package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neurosql.api.AccountVerificationController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = AccountVerificationController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "neurosql.auth.enabled=false",
        "neurosql.auth.durable-sessions-enabled=true",
        "neurosql.auth.shared-rate-limit-enabled=true",
        "neurosql.auth.rate-limit-hmac-key=test-only-verification-hmac-key-32-bytes"
})
class AccountVerificationControllerTest {
    private static final String TOKEN = "A".repeat(43);
    private static final String VERIFIED_MESSAGE = "Email verified. You can now sign in.";
    private static final String INVALID_MESSAGE =
            "This verification link is invalid or has expired. Request a new verification email.";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AccountActionTokenService actionTokens;

    @MockBean
    private SessionLifecycleService sessions;

    @MockBean
    private WorkspaceAuthorizationService workspaceAuthorizationService;

    @Test
    void validTokenReturnsSuccessWithoutEchoingTokenOrAllowingCaching() throws Exception {
        when(actionTokens.verifyEmail(TOKEN)).thenReturn(true);

        String response = mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + TOKEN + "\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(jsonPath("$.message").value(VERIFIED_MESSAGE))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain(TOKEN);
        verify(actionTokens).verifyEmail(TOKEN);
    }

    @Test
    void invalidExpiredAndReplayedTokensShareOneGenericResponse() throws Exception {
        when(actionTokens.verifyEmail(TOKEN)).thenReturn(false);

        String firstResponse = mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + TOKEN + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.message").value(INVALID_MESSAGE))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String replayResponse = mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + TOKEN + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(INVALID_MESSAGE))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(firstResponse).isEqualTo(replayResponse);
        assertThat(firstResponse).doesNotContain(TOKEN);
        org.mockito.Mockito.verify(actionTokens, org.mockito.Mockito.times(2)).verifyEmail(TOKEN);
    }

    @Test
    void malformedTokenIsRejectedBeforeTokenServiceAccess() throws Exception {
        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"not-a-token\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(actionTokens);
    }

    @Test
    void missingTokenIsRejectedBeforeTokenServiceAccess() throws Exception {
        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(actionTokens);
    }

    @Test
    void browserCanPreflightVerificationPost() throws Exception {
        mockMvc.perform(options("/api/auth/verify-email")
                        .header("Origin", "https://neurosql.vercel.app")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://neurosql.vercel.app"))
                .andExpect(header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("POST")));
    }
}
