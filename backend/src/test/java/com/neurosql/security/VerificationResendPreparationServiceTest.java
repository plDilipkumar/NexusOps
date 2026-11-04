package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VerificationResendPreparationServiceTest {
    private static final UUID USER_ID = UUID.fromString("0db5a89c-0f64-4a88-9c6f-88a8d4a840e7");
    private static final String EMAIL = "alice@example.test";
    private static final String RAW_TOKEN = "B".repeat(43);

    @Mock
    private JdbcAccountRepository accounts;
    @Mock
    private AccountActionTokenService actionTokens;

    private VerificationResendPreparationService service;
    private AccountActionTokenService.IssuedActionToken replacement;

    @BeforeEach
    void setUp() {
        service = new VerificationResendPreparationService(accounts, actionTokens);
        replacement = new AccountActionTokenService.IssuedActionToken(
                RAW_TOKEN,
                AccountActionTokenService.Purpose.EMAIL_VERIFICATION,
                Instant.parse("2026-10-30T12:00:00Z"));
    }

    @Test
    void preparesReplacementOnlyForAnActiveUnverifiedAccount() {
        when(accounts.findActiveUnverifiedByEmailForUpdate(EMAIL))
                .thenReturn(Optional.of(new JdbcAccountRepository.UnverifiedAccount(USER_ID, EMAIL)));
        when(actionTokens.replaceOutstandingToken(
                USER_ID, AccountActionTokenService.Purpose.EMAIL_VERIFICATION)).thenReturn(replacement);

        Optional<VerificationResendPreparationService.VerificationDelivery> result =
                service.prepare(" Alice@Example.test ");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().recipient()).isEqualTo(EMAIL);
        assertThat(result.orElseThrow().token()).isEqualTo(replacement);
        assertThat(result.orElseThrow().toString()).doesNotContain(RAW_TOKEN, EMAIL);
        verify(actionTokens).replaceOutstandingToken(
                USER_ID, AccountActionTokenService.Purpose.EMAIL_VERIFICATION);
    }

    @Test
    void invalidEmailDoesNotQueryTheAccountRepository() {
        assertThat(service.prepare("not-an-email")).isEmpty();
        verifyNoInteractions(accounts, actionTokens);
    }

    @Test
    void missingAccountDoesNotIssueReplacementToken() {
        when(accounts.findActiveUnverifiedByEmailForUpdate(EMAIL)).thenReturn(Optional.empty());

        assertThat(service.prepare(EMAIL)).isEmpty();
        verify(actionTokens, never()).replaceOutstandingToken(
                eq(USER_ID), eq(AccountActionTokenService.Purpose.EMAIL_VERIFICATION));
    }
}
