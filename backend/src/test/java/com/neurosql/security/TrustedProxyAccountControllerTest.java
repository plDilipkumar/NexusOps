package com.neurosql.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neurosql.api.AccountLifecycleController;
import com.neurosql.api.AccountVerificationController;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Verifies account endpoints use only the operator-configured trusted-proxy resolver for IP quotas. */
@WebMvcTest(controllers = {
        AccountLifecycleController.class,
        AccountVerificationController.class
})
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "neurosql.auth.enabled=false",
        "neurosql.auth.durable-sessions-enabled=true",
        "neurosql.auth.shared-rate-limit-enabled=true",
        "neurosql.auth.email.enabled=true",
        "neurosql.auth.rate-limit-hmac-key=test-only-trusted-proxy-hmac-key-32-bytes",
        "neurosql.security.trusted-proxy-cidrs=192.0.2.0/24",
        "spring.mail.host=smtp.example.test"
})
class TrustedProxyAccountControllerTest {
    private static final String EMAIL = "alice@example.test";
    private static final String CLIENT_IP = "198.51.100.25";
    private static final String TOKEN = "A".repeat(43);
    private static final String GENERIC_MESSAGE =
            "If the address can receive verification email, instructions will be sent.";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private VerificationEmailWorkflowService emailWorkflow;

    @MockBean
    private AccountActionTokenService actionTokens;

    @MockBean
    private SharedAccountRequestRateLimiter requestRateLimiter;

    @MockBean
    private TrustedClientAddressResolver clientAddressResolver;

    @MockBean
    private SessionLifecycleService sessions;

    @MockBean
    private WorkspaceAuthorizationService workspaceAuthorizationService;

    @BeforeEach
    void resolveOnlyThroughTrustedProxyResolver() {
        when(clientAddressResolver.resolve(any(HttpServletRequest.class))).thenReturn(CLIENT_IP);
        when(requestRateLimiter.acquireRegistration(EMAIL, CLIENT_IP))
                .thenReturn(new SharedAccountRequestRateLimiter.Decision(true, 0));
        when(requestRateLimiter.acquireVerificationConsumption(CLIENT_IP))
                .thenReturn(new SharedAccountRequestRateLimiter.Decision(true, 0));
    }

    @Test
    void registrationUsesResolvedClientIpWhenTrustedProxyRangesAreConfigured() throws Exception {
        when(emailWorkflow.registerAndSend(EMAIL, "correct-horse-12", "Alice Example"))
                .thenReturn(new VerificationEmailWorkflowService.RequestAccepted(GENERIC_MESSAGE));

        mockMvc.perform(post("/api/auth/register")
                        .header("X-Forwarded-For", "203.0.113.200")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"alice@example.test","password":"correct-horse-12","displayName":"Alice Example"}
                                """))
                .andExpect(status().isAccepted());

        verify(clientAddressResolver).resolve(any(HttpServletRequest.class));
        verify(requestRateLimiter).acquireRegistration(EMAIL, CLIENT_IP);
    }

    @Test
    void verificationConsumptionUsesResolvedClientIpWhenTrustedProxyRangesAreConfigured() throws Exception {
        when(actionTokens.verifyEmail(TOKEN)).thenReturn(true);

        mockMvc.perform(post("/api/auth/verify-email")
                        .header("X-Forwarded-For", "203.0.113.200")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + TOKEN + "\"}"))
                .andExpect(status().isOk());

        verify(clientAddressResolver).resolve(any(HttpServletRequest.class));
        verify(requestRateLimiter).acquireVerificationConsumption(CLIENT_IP);
    }

    @Test
    void unresolvedClientAddressFailsClosedBeforeEmailOrTokenWorkflows() throws Exception {
        when(clientAddressResolver.resolve(any(HttpServletRequest.class))).thenReturn("unknown");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"alice@example.test","password":"correct-horse-12","displayName":"Alice Example"}
                                """))
                .andExpect(status().isServiceUnavailable());

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + TOKEN + "\"}"))
                .andExpect(status().isServiceUnavailable());

        verifyNoInteractions(requestRateLimiter, emailWorkflow, actionTokens);
    }
}
