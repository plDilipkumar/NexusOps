package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class RegistrationServiceTest {
    private static final String EMAIL = "alice@example.test";
    private static final String PASSWORD = "correct-horse-12";
    private static final String DISPLAY_NAME = "Alice Example";
    private static final String PASSWORD_HASH = "$2a$10$" + "a".repeat(53);
    private static final UUID USER_ID = UUID.fromString("0db5a89c-0f64-4a88-9c6f-88a8d4a840e7");
    private static final String RAW_TOKEN = "A".repeat(43);

    @Mock
    private JdbcAccountRepository accounts;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AccountActionTokenService actionTokens;

    private RegistrationService service;

    @BeforeEach
    void setUp() {
        service = new RegistrationService(accounts, passwordEncoder, actionTokens);
    }

    @Test
    void createsUnverifiedAccountAndIssuesEmailVerificationToken() {
        when(passwordEncoder.encode(PASSWORD)).thenReturn(PASSWORD_HASH);
        when(accounts.createUnverifiedAccount(EMAIL, PASSWORD_HASH, DISPLAY_NAME))
                .thenReturn(Optional.of(USER_ID));
        AccountActionTokenService.IssuedActionToken issued =
                new AccountActionTokenService.IssuedActionToken(
                        RAW_TOKEN,
                        AccountActionTokenService.Purpose.EMAIL_VERIFICATION,
                        Instant.parse("2026-10-30T12:00:00Z"));
        when(actionTokens.issueToken(USER_ID, AccountActionTokenService.Purpose.EMAIL_VERIFICATION))
                .thenReturn(issued);

        RegistrationService.RegistrationAttempt result =
                service.register(" Alice@Example.test ", PASSWORD, " Alice Example ");

        assertThat(result.verificationDelivery()).contains(issued);
        assertThat(result.toString()).doesNotContain(RAW_TOKEN);
        verify(passwordEncoder).encode(PASSWORD);
        verify(accounts).createUnverifiedAccount(EMAIL, PASSWORD_HASH, DISPLAY_NAME);
        verify(actionTokens).issueToken(USER_ID, AccountActionTokenService.Purpose.EMAIL_VERIFICATION);
    }

    @Test
    void duplicateEmailDoesNotIssueAnotherVerificationToken() {
        when(passwordEncoder.encode(PASSWORD)).thenReturn(PASSWORD_HASH);
        when(accounts.createUnverifiedAccount(EMAIL, PASSWORD_HASH, DISPLAY_NAME))
                .thenReturn(Optional.empty());

        RegistrationService.RegistrationAttempt result =
                service.register(EMAIL, PASSWORD, DISPLAY_NAME);

        assertThat(result.verificationDelivery()).isEmpty();
        verify(passwordEncoder).encode(PASSWORD);
        verify(actionTokens, never()).issueToken(any(UUID.class), any(AccountActionTokenService.Purpose.class));
    }

    @Test
    void rejectsWeakPasswordBeforeHashingOrWritingAnAccount() {
        assertThatThrownBy(() -> service.register(EMAIL, "short", DISPLAY_NAME))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("12 characters");

        verify(passwordEncoder, never()).encode(any(String.class));
        verify(accounts, never()).createUnverifiedAccount(any(), any(), any());
        verify(actionTokens, never()).issueToken(any(UUID.class), any(AccountActionTokenService.Purpose.class));
    }

    @Test
    void rejectsMalformedEmailBeforeExpensivePasswordHashing() {
        assertThatThrownBy(() -> service.register("not-an-email", PASSWORD, DISPLAY_NAME))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email");

        verify(passwordEncoder, never()).encode(any(String.class));
        verify(accounts, never()).createUnverifiedAccount(any(), any(), any());
    }

    @Test
    void rejectsInvalidDisplayNameBeforePasswordHashing() {
        assertThatThrownBy(() -> service.register(EMAIL, PASSWORD, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("display name");

        verify(passwordEncoder, never()).encode(any(String.class));
        verify(accounts, never()).createUnverifiedAccount(any(), any(), any());
    }
}
