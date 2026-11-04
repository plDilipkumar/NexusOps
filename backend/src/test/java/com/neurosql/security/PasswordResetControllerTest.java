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

import com.neurosql.api.PasswordResetController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = PasswordResetController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "neurosql.auth.enabled=false",
        "neurosql.auth.durable-sessions-enabled=true",
        "neurosql.auth.shared-rate-limit-enabled=true",
        "neurosql.auth.rate-limit-hmac-key=test-only-password-reset-hmac-key-32-bytes"
})
class PasswordResetControllerTest {
    private static final String TOKEN = "A".repeat(43);
    private static final String PASSWORD = "New-Correct-Horse-2026";
    private static final String SUCCESS_MESSAGE =
            "Your password has been reset. Please sign in again.";
    private static final String INVALID_TOKEN_MESSAGE =
            "This password-reset link is invalid or has expired. Request a new link.";
    private static final String INVALID_REQUEST_MESSAGE =
            "A valid reset token and new password are required.";
    private static final String PASSWORD_POLICY_MESSAGE =
            "The new password does not meet the security requirements.";
    private static final String TEMPORARY_FAILURE_MESSAGE =
            "Password reset is temporarily unavailable. Try again later.";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PasswordResetService passwordResetService;

    @MockBean
    private SessionLifecycleService sessions;

    @MockBean
    private WorkspaceAuthorizationService workspaceAuthorizationService;

    @Test
    void validTokenReturnsGenericSuccessWithoutEchoingTokenOrPassword() throws Exception {
        when(passwordResetService.resetPassword(TOKEN, PASSWORD))
                .thenReturn(new PasswordResetService.ResetResult(true, 2));

        String response = mockMvc.perform(post("/api/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + TOKEN + "\",\"newPassword\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(jsonPath("$.message").value(SUCCESS_MESSAGE))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain(TOKEN, PASSWORD);
        verify(passwordResetService).resetPassword(TOKEN, PASSWORD);
    }

    @Test
    void invalidExpiredAndReplayedTokensShareOneGenericResponse() throws Exception {
        when(passwordResetService.resetPassword(TOKEN, PASSWORD))
                .thenReturn(new PasswordResetService.ResetResult(false, 0));

        String firstResponse = mockMvc.perform(post("/api/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + TOKEN + "\",\"newPassword\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.message").value(INVALID_TOKEN_MESSAGE))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String replayResponse = mockMvc.perform(post("/api/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + TOKEN + "\",\"newPassword\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(INVALID_TOKEN_MESSAGE))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(firstResponse).isEqualTo(replayResponse);
        assertThat(firstResponse).doesNotContain(TOKEN, PASSWORD);
        org.mockito.Mockito.verify(passwordResetService, org.mockito.Mockito.times(2))
                .resetPassword(TOKEN, PASSWORD);
    }

    @Test
    void passwordPolicyFailureReturnsGenericMessageWithoutLeakingDetails() throws Exception {
        when(passwordResetService.resetPassword(TOKEN, "short"))
                .thenThrow(new IllegalArgumentException("internal password policy detail"));

        String response = mockMvc.perform(post("/api/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + TOKEN + "\",\"newPassword\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(PASSWORD_POLICY_MESSAGE))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain("internal password policy detail", "short");
    }

    @Test
    void persistenceFailureReturnsGenericTemporaryFailure() throws Exception {
        when(passwordResetService.resetPassword(TOKEN, PASSWORD))
                .thenThrow(new IllegalStateException("database or account details"));

        String response = mockMvc.perform(post("/api/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + TOKEN + "\",\"newPassword\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.message").value(TEMPORARY_FAILURE_MESSAGE))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain("database or account details", TOKEN, PASSWORD);
    }

    @Test
    void malformedTokenAndMissingFieldsAreRejectedBeforeServiceAccess() throws Exception {
        mockMvc.perform(post("/api/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"not-a-token\",\"newPassword\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(INVALID_REQUEST_MESSAGE));

        mockMvc.perform(post("/api/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(INVALID_REQUEST_MESSAGE));

        verifyNoInteractions(passwordResetService);
    }

    @Test
    void unreadableJsonReturnsSafeMessage() throws Exception {
        mockMvc.perform(post("/api/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(INVALID_REQUEST_MESSAGE));

        verifyNoInteractions(passwordResetService);
    }

    @Test
    void browserCanPreflightPasswordResetPostWithoutSmtpConfiguration() throws Exception {
        mockMvc.perform(options("/api/auth/password-reset/confirm")
                        .header("Origin", "https://neurosql.vercel.app")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        "https://neurosql.vercel.app"));
    }
}
