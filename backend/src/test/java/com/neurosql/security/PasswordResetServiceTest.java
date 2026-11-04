package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {
    private static final String PASSWORD = "Correct-Horse-Battery-2026";
    private static final String HASH = "$2a$10$" + "x".repeat(53);

    @Mock
    private AccountActionTokenService actionTokens;

    @Mock
    private JdbcAccountRepository accounts;

    @Mock
    private JdbcSessionRepository sessions;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private PasswordResetService service;

    @Test
    void consumesResetTokenUpdatesEncodedPasswordAndRevokesEverySessionInOrder() {
        UUID userId = UUID.randomUUID();
        when(actionTokens.consumeToken("valid-reset-token", AccountActionTokenService.Purpose.PASSWORD_RESET))
                .thenReturn(Optional.of(userId));
        when(passwordEncoder.encode(PASSWORD)).thenReturn(HASH);
        when(accounts.updatePasswordHashForActiveVerifiedUser(userId, HASH)).thenReturn(1);
        when(sessions.revokeAllForUser(userId)).thenReturn(3);

        PasswordResetService.ResetResult result = service.resetPassword("valid-reset-token", PASSWORD);

        assertThat(result.successful()).isTrue();
        assertThat(result.revokedSessions()).isEqualTo(3);
        InOrder order = inOrder(actionTokens, accounts, sessions);
        order.verify(actionTokens).consumeToken(
                "valid-reset-token", AccountActionTokenService.Purpose.PASSWORD_RESET);
        order.verify(accounts).updatePasswordHashForActiveVerifiedUser(userId, HASH);
        order.verify(sessions).revokeAllForUser(userId);
    }

    @Test
    void invalidOrExpiredTokenDoesNotEncodeOrChangeCredentials() {
        when(actionTokens.consumeToken("invalid-token", AccountActionTokenService.Purpose.PASSWORD_RESET))
                .thenReturn(Optional.empty());

        PasswordResetService.ResetResult result = service.resetPassword("invalid-token", PASSWORD);

        assertThat(result.successful()).isFalse();
        assertThat(result.revokedSessions()).isZero();
        verifyNoInteractions(passwordEncoder, accounts, sessions);
    }

    @Test
    void invalidPasswordIsRejectedBeforeTokenConsumption() {
        assertThatThrownBy(() -> service.resetPassword("valid-reset-token", "short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 12 characters");

        verifyNoInteractions(actionTokens, passwordEncoder, accounts, sessions);
    }

    @Test
    void accountThatIsNoLongerActiveAndVerifiedCausesTransactionFailureBeforeSessionRevocation() {
        UUID userId = UUID.randomUUID();
        when(actionTokens.consumeToken("valid-reset-token", AccountActionTokenService.Purpose.PASSWORD_RESET))
                .thenReturn(Optional.of(userId));
        when(passwordEncoder.encode(PASSWORD)).thenReturn(HASH);
        when(accounts.updatePasswordHashForActiveVerifiedUser(userId, HASH)).thenReturn(0);

        assertThatThrownBy(() -> service.resetPassword("valid-reset-token", PASSWORD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The password reset could not be completed.");

        verify(sessions, never()).revokeAllForUser(userId);
    }
}
