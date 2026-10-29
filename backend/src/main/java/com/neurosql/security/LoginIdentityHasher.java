package com.neurosql.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Produces a keyed digest so raw login identities never enter shared rate-limit storage. */
final class LoginIdentityHasher {
    private static final String INVALID_IDENTITY = "__invalid_login_identity__";
    private static final int MINIMUM_KEY_BYTES = 32;

    private final SecretKeySpec key;

    LoginIdentityHasher(String keyMaterial) {
        byte[] keyBytes = keyMaterial == null ? new byte[0] : keyMaterial.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MINIMUM_KEY_BYTES) {
            throw new IllegalArgumentException("The shared rate-limit HMAC key must contain at least 32 UTF-8 bytes.");
        }
        this.key = new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    String hash(String identity) {
        String normalized = normalize(identity);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException unavailableAlgorithm) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable.", unavailableAlgorithm);
        }
    }

    private static String normalize(String identity) {
        if (identity == null || identity.isBlank() || identity.length() > 254) {
            return INVALID_IDENTITY;
        }
        String normalized = identity.strip().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() || normalized.length() > 254 ? INVALID_IDENTITY : normalized;
    }
}
