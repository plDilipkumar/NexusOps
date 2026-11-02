package com.neurosql.security;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neurosql.api.AccountLifecycleController;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = AccountLifecycleController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "neurosql.auth.enabled=false",
        "neurosql.auth.durable-sessions-enabled=true",
        "neurosql.auth.shared-rate-limit-enabled=true",
        "neurosql.auth.email.enabled=true",
        "neurosql.auth.rate-limit-hmac-key=test-only-verification-hmac-key-32-bytes",
        "spring.mail.host=smtp.example.test"
})
class AccountLifecycleControllerTest {
    private static final String EMAIL = "alice@example.test";
    private static final String PASSWORD = "correct-horse-12";
    private static final String DISPLAY_NAME = "Alice Example";
    private static final String GENERIC_MESSAGE =
            "If the address can receive verification email, instructions will be sent.";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private VerificationEmailWorkflowService emailWorkflow;

    @MockBean
    private VerificationRequestRateLimiter requestRateLimiter;

    @MockBean
    private TrustedClientAddressResolver clientAddressResolver;

    @MockBean
    private SessionLifecycleService sessions;

    @MockBean
    private WorkspaceAuthorizationService workspaceAuthorizationService;

    @Test
    void registrationReturnsGenericAcceptedResponseWithoutAccountEnumeration() throws Exception {
        when(requestRateLimiter.acquire(VerificationRequestRateLimiter.Purpose.REGISTRATION, EMAIL))
                .thenReturn(new VerificationRequestRateLimiter.Decision(true, 0));
        when(emailWorkflow.registerAndSend(EMAIL, PASSWORD, DISPLAY_NAME))
                .thenReturn(new VerificationEmailWorkflowService.RequestAccepted(GENERIC_MESSAGE));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"alice@example.test","password":"correct-horse-12","displayName":"Alice Example"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(jsonPath("$.message").value(GENERIC_MESSAGE))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.verificationToken").doesNotExist());

        verify(requestRateLimiter).acquire(VerificationRequestRateLimiter.Purpose.REGISTRATION, EMAIL);
        verify(emailWorkflow).registerAndSend(EMAIL, PASSWORD, DISPLAY_NAME);
        verifyNoInteractions(clientAddressResolver);
    }

    @Test
    void verificationResendReturnsTheSameGenericResponseForUnknownAddresses() throws Exception {
        when(requestRateLimiter.acquire(VerificationRequestRateLimiter.Purpose.RESEND, EMAIL))
                .thenReturn(new VerificationRequestRateLimiter.Decision(true, 0));
        when(emailWorkflow.resendVerification(EMAIL))
                .thenReturn(new VerificationEmailWorkflowService.RequestAccepted(GENERIC_MESSAGE));

        mockMvc.perform(post("/api/auth/verification/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.test\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.message").value(GENERIC_MESSAGE))
                .andExpect(jsonPath("$.token").doesNotExist());

        verify(requestRateLimiter).acquire(VerificationRequestRateLimiter.Purpose.RESEND, EMAIL);
        verify(emailWorkflow).resendVerification(EMAIL);
    }

    @Test
    void rateLimitedRegistrationReturnsRetryAfterWithoutCallingEmailWorkflow() throws Exception {
        when(requestRateLimiter.acquire(VerificationRequestRateLimiter.Purpose.REGISTRATION, EMAIL))
                .thenReturn(new VerificationRequestRateLimiter.Decision(false, 300));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"alice@example.test","password":"correct-horse-12","displayName":"Alice Example"}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "300"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.message").value("Too many requests. Try again later."));

        verify(emailWorkflow, never()).registerAndSend(eq(EMAIL), eq(PASSWORD), eq(DISPLAY_NAME));
    }

    @Test
    void registrationPolicyFailureReturnsGenericClientErrorWithoutLeakingDetails() throws Exception {
        when(requestRateLimiter.acquire(VerificationRequestRateLimiter.Purpose.REGISTRATION, EMAIL))
                .thenReturn(new VerificationRequestRateLimiter.Decision(true, 0));
        when(emailWorkflow.registerAndSend(EMAIL, PASSWORD, DISPLAY_NAME))
                .thenThrow(new IllegalArgumentException("internal password policy detail"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"alice@example.test","password":"correct-horse-12","displayName":"Alice Example"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.message").value("Registration details are invalid."))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(containsString("internal"))));
    }

    @Test
    void invalidEmailIsRejectedByRequestValidationBeforeRateLimitOrPersistence() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email","password":"correct-horse-12","displayName":"Alice Example"}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(requestRateLimiter, emailWorkflow);
    }

    @Test
    void browserCanPreflightRegistrationPost() throws Exception {
        mockMvc.perform(options("/api/auth/register")
                        .header("Origin", "https://neurosql.vercel.app")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://neurosql.vercel.app"))
                .andExpect(header().string("Access-Control-Allow-Methods", containsString("POST")));
    }

    @Test
    void forwardedAddressIsNotTrustedWhenProxyRangesAreNotConfigured() throws Exception {
        when(requestRateLimiter.acquire(VerificationRequestRateLimiter.Purpose.RESEND, EMAIL))
                .thenReturn(new VerificationRequestRateLimiter.Decision(true, 0));
        when(emailWorkflow.resendVerification(EMAIL))
                .thenReturn(new VerificationEmailWorkflowService.RequestAccepted(GENERIC_MESSAGE));

        mockMvc.perform(post("/api/auth/verification/resend")
                        .header("X-Forwarded-For", "198.51.100.25")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.test\"}"))
                .andExpect(status().isAccepted());

        verify(requestRateLimiter).acquire(VerificationRequestRateLimiter.Purpose.RESEND, EMAIL);
        verifyNoInteractions(clientAddressResolver);
    }
}
