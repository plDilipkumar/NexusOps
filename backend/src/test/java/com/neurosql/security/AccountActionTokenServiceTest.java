package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountActionTokenServiceTest {
    private static final UUID USER_ID = UUID.fromString("4b1c3f16-ec2f-43a1-a7b1-6b2a09f781de");
    private static final Instant EXPIRES_AT = Instant.parse("2026-10-30T12:00:00Z");

    @Mock
    private JdbcAccountActionTokenRepository tokens;

    private final SessionTokenCodec codec = new SessionTokenCodec();
    private AccountActionTokenService service;

    @BeforeEach
    void setUp() {
        service = new AccountActionTokenService(codec, tokens);
    }

    @Test
    void issuesEmailVerificationTokenAndPersistsOnlyItsDigest() {
        when(tokens.createToken(
                eq(USER_ID),
                eq(AccountActionTokenService.Purpose.EMAIL_VERIFICATION),
                anyString(),
                eq(86_400L))).thenReturn(EXPIRES_AT);

        AccountActionTokenService.IssuedActionToken issued =
                service.issueToken(USER_ID, AccountActionTokenService.Purpose.EMAIL_VERIFICATION);

        ArgumentCaptor<String> digest = ArgumentCaptor.forClass(String.class);
        verify(tokens).createToken(
                eq(USER_ID),
                eq(AccountActionTokenService.Purpose.EMAIL_VERIFICATION),
                digest.capture(),
                eq(86_400L));
        assertThat(issued.token()).matches("[A-Za-z0-9_-]{43}");
        assertThat(digest.getValue()).isEqualTo(codec.hashToken(issued.token()));
        assertThat(digest.getValue()).isNotEqualTo(issued.token());
        assertThat(issued.expiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(issued.toString()).doesNotContain(issued.token());
    }

    @Test
    void passwordResetTokensUseThirtyMinuteLifetime() {
        when(tokens.createToken(
                eq(USER_ID),
                eq(AccountActionTokenService.Purpose.PASSWORD_RESET),
                anyString(),
                eq(1_800L))).thenReturn(EXPIRES_AT);

        AccountActionTokenService.IssuedActionToken issued =
                service.issueToken(USER_ID, AccountActionTokenService.Purpose.PASSWORD_RESET);

        assertThat(issued.purpose()).isEqualTo(AccountActionTokenService.Purpose.PASSWORD_RESET);
        verify(tokens).createToken(
                eq(USER_ID),
                eq(AccountActionTokenService.Purpose.PASSWORD_RESET),
                anyString(),
                eq(1_800L));
    }

    @Test
    void consumesByDigestAndExpectedPurpose() {
        String rawToken = codec.issueToken();
        when(tokens.consumeToken(
                codec.hashToken(rawToken),
                AccountActionTokenService.Purpose.PASSWORD_RESET))
                .thenReturn(Optional.of(USER_ID));

        assertThat(service.consumeToken(rawToken, AccountActionTokenService.Purpose.PASSWORD_RESET))
                .contains(USER_ID);
        verify(tokens).consumeToken(
                codec.hashToken(rawToken),
                AccountActionTokenService.Purpose.PASSWORD_RESET);
    }

    @Test
    void malformedTokenFailsClosedWithoutDatabaseAccess() {
        assertThat(service.consumeToken("not-a-token", AccountActionTokenService.Purpose.PASSWORD_RESET))
                .isEmpty();
        verifyNoInteractions(tokens);
    }

    @Test
    void requiresUserAndPurposeWhenIssuing() {
        assertThatThrownBy(() -> service.issueToken(null, AccountActionTokenService.Purpose.PASSWORD_RESET))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.issueToken(USER_ID, null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(tokens);
    }
}
