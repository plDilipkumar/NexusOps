package com.neurosql.security;

import java.nio.charset.StandardCharsets;

/**
 * Validates passwords before BCrypt hashing.
 *
 * <p>BCrypt implementations commonly process at most 72 UTF-8 bytes. Rejecting
 * longer values avoids accepting distinct passwords that hash as the same
 * truncated input. This policy is applied when the environment-configured owner
 * account is enabled and can be reused by a future registration/reset flow.</p>
 */
public final class PasswordPolicy {
    public static final int MIN_CODE_POINTS = 12;
    public static final int MAX_UTF8_BYTES = 72;

    private PasswordPolicy() {
    }

    public static void validate(String password) {
        if (password == null) {
            throw new IllegalArgumentException("Password must be provided.");
        }
        if (password.codePointCount(0, password.length()) < MIN_CODE_POINTS) {
            throw new IllegalArgumentException("Password must contain at least 12 characters.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES) {
            throw new IllegalArgumentException("Password must not exceed 72 UTF-8 bytes.");
        }
        if (password.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Password must not contain control characters.");
        }
    }
}
