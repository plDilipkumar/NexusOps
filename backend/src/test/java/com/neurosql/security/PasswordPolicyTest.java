package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {
    @Test
    void acceptsPasswordMeetingMinimumLength() {
        assertThatCode(() -> PasswordPolicy.validate("correct-horse-12")).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingOrShortPasswords() {
        assertThatThrownBy(() -> PasswordPolicy.validate(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PasswordPolicy.validate("short-pass"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPasswordsBeyondBcryptUtf8ByteLimit() {
        assertThatThrownBy(() -> PasswordPolicy.validate("é".repeat(37)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("72 UTF-8 bytes");
    }

    @Test
    void acceptsExactlySeventyTwoUtf8Bytes() {
        assertThatCode(() -> PasswordPolicy.validate("é".repeat(36))).doesNotThrowAnyException();
    }

    @Test
    void rejectsControlCharactersWithoutTrimmingOrNormalizingPassword() {
        assertThatThrownBy(() -> PasswordPolicy.validate("long-enough\npass"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control characters");
        assertThatCode(() -> PasswordPolicy.validate("  long-enough  ")).doesNotThrowAnyException();
    }
}
