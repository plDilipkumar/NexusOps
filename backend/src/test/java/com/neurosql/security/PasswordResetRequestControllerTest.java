package com.neurosql.security;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neurosql.api.PasswordResetRequestController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = PasswordResetRequestController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "neurosql.auth.enabled=false",
        "neurosql.auth.durable-sessions-enabled=true",
        "neurosql.auth.shared-rate-limit-enabled=true",
        "neurosql.auth.email.enabled=true",
        "neurosql.auth.rate-limit-hmac-key=test-only-password-reset-hmac-key-32-bytes",
        "neurosql.auth.email.verification-url=https://neurosql.vercel.app/verify-email",
        "neurosql.auth.email.password-reset-url=https://neurosql.vercel.app/reset-password",
        "spring.mail.host=smtp.example.test"
})
class PasswordResetRequestControllerTest {
    private static final String EMAIL = "alice@example.test";
    private static final String GENERIC_MESSAGE =
            "If an account exists for that address, a password-reset link will be sent.";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PasswordResetEmailWorkflowService workflow;
    @MockBean
    private SharedAccountRequestRateLimiter rateLimiter;
    @MockBean
    private TrustedClientAddressResolver clientAddressResolver;
    @MockBean
    private SessionLifecycleService sessions;
    @MockBean
    private WorkspaceAuthorizationService workspaceAuthorizationService;

    @Test
    void requestReturnsGenericAcceptedResponseWithoutRevealingAccountState() throws Exception {
        when(rateLimiter.acquirePasswordReset(EMAIL, null))
                .thenReturn(new SharedAccountRequestRateLimiter.Decision(true, 0));
        when(workflow.requestReset(EMAIL))
                .thenReturn(new PasswordResetEmailWorkflowService.RequestAccepted(GENERIC_MESSAGE));

        mockMvc.perform(post("/api/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.test\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(jsonPath("$.message").value(GENERIC_MESSAGE))
                .andExpect(jsonPath("$.token").doesNotExist());

        verify(rateLimiter).acquirePasswordReset(EMAIL, null);
        verify(workflow).requestReset(EMAIL);
        verifyNoInteractions(clientAddressResolver);
    }

    @Test
    void rateLimitReturnsRetryAfterAndDoesNotPrepareAToken() throws Exception {
        when(rateLimiter.acquirePasswordReset(EMAIL, null))
                .thenReturn(new SharedAccountRequestRateLimiter.Decision(false, 300));

        mockMvc.perform(post("/api/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.test\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "300"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.message").value("Too many requests. Try again later."));

        verify(workflow, never()).requestReset(EMAIL);
    }

    @Test
    void invalidEmailIsRejectedBeforeRateLimitOrAccountLookup() throws Exception {
        mockMvc.perform(post("/api/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(rateLimiter, workflow);
    }

    @Test
    void callerForwardedHeaderIsIgnoredWithoutConfiguredTrustedProxyRanges() throws Exception {
        when(rateLimiter.acquirePasswordReset(EMAIL, null))
                .thenReturn(new SharedAccountRequestRateLimiter.Decision(true, 0));
        when(workflow.requestReset(EMAIL))
                .thenReturn(new PasswordResetEmailWorkflowService.RequestAccepted(GENERIC_MESSAGE));

        mockMvc.perform(post("/api/auth/password-reset/request")
                        .header("X-Forwarded-For", "198.51.100.25")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.test\"}"))
                .andExpect(status().isAccepted());

        verify(rateLimiter).acquirePasswordReset(EMAIL, null);
        verifyNoInteractions(clientAddressResolver);
    }

    @Test
    void browserCanPreflightPasswordResetRequestPost() throws Exception {
        mockMvc.perform(options("/api/auth/password-reset/request")
                        .header("Origin", "https://neurosql.vercel.app")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://neurosql.vercel.app"))
                .andExpect(header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("POST")));
    }

    @Test
    void sharedLimiterFailureFailsClosedWithoutCallingEmailWorkflow() throws Exception {
        when(rateLimiter.acquirePasswordReset(EMAIL, null))
                .thenThrow(new IllegalStateException("database unavailable"));

        mockMvc.perform(post("/api/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.test\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));

        verify(workflow, never()).requestReset(EMAIL);
    }
}
