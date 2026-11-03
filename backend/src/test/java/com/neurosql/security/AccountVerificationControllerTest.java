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
import org.junit.jupiter.api.BeforeEach;
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
    private static final String INVALID_REQUEST_MESSAGE = "A valid verification token is required.";
    private static final String TEMPORARY_FAILURE_MESSAGE =
            "Email verification is temporarily unavailable. Try again later.";

    @Autowired
    private MockMvc mockMvc;

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
    void allowRequestsWithoutConfiguredTrustedProxyRanges() {
        when(requestRateLimiter.acquireVerificationConsumption(null))
                .thenReturn(new SharedAccountRequestRateLimiter.Decision(true, 0));
    }

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
        verify(requestRateLimiter).acquireVerificationConsumption(null);
        verify(actionTokens).verifyEmail(TOKEN);
        verifyNoInteractions(clientAddressResolver);
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
        verify(requestRateLimiter, org.mockito.Mockito.times(2)).acquireVerificationConsumption(null);
        org.mockito.Mockito.verify(actionTokens, org.mockito.Mockito.times(2)).verifyEmail(TOKEN);
    }

    @Test
    void malformedTokenIsRejectedBeforeRateLimiterOrTokenServiceAccess() throws Exception {
        String response = mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"not-a-token\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(INVALID_REQUEST_MESSAGE))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain("not-a-token");
        verifyNoInteractions(actionTokens, requestRateLimiter);
    }

    @Test
    void missingTokenIsRejectedBeforeRateLimiterOrTokenServiceAccess() throws Exception {
        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(INVALID_REQUEST_MESSAGE));

        verifyNoInteractions(actionTokens, requestRateLimiter);
    }

    @Test
    void unreadableJsonReturnsSafeMessageBeforeRateLimiterOrTokenServiceAccess() throws Exception {
        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(INVALID_REQUEST_MESSAGE));

        verifyNoInteractions(actionTokens, requestRateLimiter);
    }

    @Test
    void rateLimitedVerificationDoesNotConsumeToken() throws Exception {
        when(requestRateLimiter.acquireVerificationConsumption(null))
                .thenReturn(new SharedAccountRequestRateLimiter.Decision(false, 300));

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + TOKEN + "\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "300"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.message").value("Too many verification attempts. Try again later."));

        verifyNoInteractions(actionTokens);
    }

    @Test
    void sharedLimiterFailureFailsClosedWithoutConsumingToken() throws Exception {
        when(requestRateLimiter.acquireVerificationConsumption(null))
                .thenThrow(new IllegalStateException("sensitive database details"));

        String response = mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + TOKEN + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.message").value(TEMPORARY_FAILURE_MESSAGE))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain("sensitive");
        verifyNoInteractions(actionTokens);
    }

    @Test
    void tokenPersistenceFailureReturnsGenericUnavailableResponse() throws Exception {
        when(actionTokens.verifyEmail(TOKEN)).thenThrow(new IllegalStateException("database details"));

        String response = mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + TOKEN + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(TEMPORARY_FAILURE_MESSAGE))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain("database details");
    }

    @Test
    void forwardedAddressIsIgnoredWhenTrustedProxyRangesAreNotConfigured() throws Exception {
        when(actionTokens.verifyEmail(TOKEN)).thenReturn(true);

        mockMvc.perform(post("/api/auth/verify-email")
                        .header("X-Forwarded-For", "198.51.100.25")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + TOKEN + "\"}"))
                .andExpect(status().isOk());

        verify(requestRateLimiter).acquireVerificationConsumption(null);
        verifyNoInteractions(clientAddressResolver);
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
