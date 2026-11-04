package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;
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
class PasswordResetRequestPreparationServiceTest {
    private static final String EMAIL = "alice@example.test";
    private static final UUID USER_ID = UUID.fromString("4b1c3f16-ec2f-43a1-a7b1-6b2a09f781de");
    private static final String RAW_TOKEN = "A".repeat(43);

    @Mock
    private JdbcAccountRepository accounts;
    @Mock
    private AccountActionTokenService actionTokens;

    private PasswordResetRequestPreparationService service;
    private AccountActionTokenService.IssuedActionToken token;

    @BeforeEach
    void setUp() {
        service = new PasswordResetRequestPreparationService(accounts, actionTokens);
        token = new AccountActionTokenService.IssuedActionToken(
                RAW_TOKEN,
                AccountActionTokenService.Purpose.PASSWORD_RESET,
                Instant.parse("2026-11-03T08:00:00Z"));
    }

    @Test
    void locksVerifiedAccountAndReplacesOutstandingResetToken() {
        when(accounts.findActiveVerifiedByEmailForUpdate(EMAIL))
                .thenReturn(Optional.of(new JdbcAccountRepository.LoginAccount(USER_ID, "x".repeat(60))));
        when(actionTokens.replaceOutstandingToken(USER_ID, AccountActionTokenService.Purpose.PASSWORD_RESET))
                .thenReturn(token);

        Optional<PasswordResetRequestPreparationService.PasswordResetDelivery> result =
                service.prepare(" Alice@Example.test ");

        assertThat(result).isPresent();
        PasswordResetRequestPreparationService.PasswordResetDelivery delivery = result.orElseThrow();
        assertThat(delivery.recipient()).isEqualTo(EMAIL);
        assertThat(delivery.token()).isEqualTo(token);
        assertThat(delivery.toString()).doesNotContain(EMAIL, RAW_TOKEN);
        verify(accounts).findActiveVerifiedByEmailForUpdate(EMAIL);
        verify(actionTokens).replaceOutstandingToken(USER_ID, AccountActionTokenService.Purpose.PASSWORD_RESET);
    }

    @Test
    void unknownOrIneligibleAccountProducesNoDelivery() {
        when(accounts.findActiveVerifiedByEmailForUpdate(EMAIL)).thenReturn(Optional.empty());

        assertThat(service.prepare(EMAIL)).isEmpty();
        verify(accounts).findActiveVerifiedByEmailForUpdate(EMAIL);
        verifyNoInteractions(actionTokens);
    }

    @Test
    void malformedEmailDoesNotReachAccountRepository() {
        assertThat(service.prepare("not-an-email")).isEmpty();
        verifyNoInteractions(accounts, actionTokens);
    }

    @Test
    void rejectsWrongPurposeDeliveryRecords() {
        AccountActionTokenService.IssuedActionToken verification =
                new AccountActionTokenService.IssuedActionToken(
                        RAW_TOKEN,
                        AccountActionTokenService.Purpose.EMAIL_VERIFICATION,
                        Instant.parse("2026-11-03T08:00:00Z"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new PasswordResetRequestPreparationService.PasswordResetDelivery(EMAIL, verification))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
