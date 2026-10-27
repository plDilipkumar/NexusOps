package com.neurosql.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Creates opaque bearer tokens for the proposed durable-session flow.
 *
 * <p>Only {@link #hashToken(String)} output belongs in the database. Keep the
 * raw token in the response to the client and never log or persist it.</p>
 *
 * <p>This primitive is intentionally not wired into HTTP authentication until
 * the durable-auth schema is approved and session lifecycle controls are ready.</p>
 */
public final class SessionTokenCodec {
    private static final int TOKEN_BYTES = 32;
    private static final int TOKEN_LENGTH = 43;
    private static final String TOKEN_PATTERN = "[A-Za-z0-9_-]{43}";

    private final SecureRandom secureRandom;

    public SessionTokenCodec() {
        this(new SecureRandom());
    }

    SessionTokenCodec(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    /** Issues a 256-bit URL-safe token without padding. */
    public String issueToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Returns the lowercase SHA-256 hex digest suitable for the proposed
     * neurosql_app.user_sessions.token_hash column.
     *
     * @throws IllegalArgumentException if the input is not a canonical token
     */
    public String hashToken(String rawToken) {
        if (!isCanonicalToken(rawToken)) {
            throw new IllegalArgumentException("Session token has an invalid format.");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable in this runtime.", impossible);
        }
    }

    private boolean isCanonicalToken(String token) {
        if (token == null || token.length() != TOKEN_LENGTH || !token.matches(TOKEN_PATTERN)) {
            return false;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(token);
            return decoded.length == TOKEN_BYTES
                    && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(token);
        } catch (IllegalArgumentException invalidBase64) {
            return false;
        }
    }
}
