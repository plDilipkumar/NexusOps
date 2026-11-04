package com.neurosql.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Versioned keyset cursor for a workspace-scoped saved-query search.
 *
 * <p>The cursor binds the workspace and normalized search fingerprint to the
 * page position. It is navigation state, not an authorization credential;
 * repository reads remain scoped to the workspace authorized by the request
 * filter.</p>
 */
final class SavedQueryCursor {
    private static final String VERSION = "v2";
    private static final int MAX_ENCODED_LENGTH = 384;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private SavedQueryCursor() {
    }

    static String encode(UUID workspaceId, String search, Instant updatedAt, UUID savedQueryId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(savedQueryId, "savedQueryId");
        if (updatedAt.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException("Saved-query timestamps must have PostgreSQL microsecond precision.");
        }

        String payload = String.join(
                "|",
                VERSION,
                workspaceId.toString(),
                searchFingerprint(search),
                updatedAt.toString(),
                savedQueryId.toString());
        String encoded = ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        if (encoded.length() > MAX_ENCODED_LENGTH) {
            throw new IllegalArgumentException("Saved-query cursor is too large.");
        }
        return encoded;
    }

    static Position decode(String encoded, UUID workspaceId, String search) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        if (encoded == null || encoded.isBlank() || encoded.length() > MAX_ENCODED_LENGTH) {
            throw new IllegalArgumentException("Invalid saved-query cursor.");
        }

        try {
            String payload = new String(DECODER.decode(encoded), StandardCharsets.UTF_8);
            if (!ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8)).equals(encoded)) {
                throw new IllegalArgumentException("Invalid saved-query cursor.");
            }

            String[] fields = payload.split("\\|", -1);
            if (fields.length != 5
                    || !VERSION.equals(fields[0])
                    || !workspaceId.toString().equals(fields[1])
                    || !searchFingerprint(search).equals(fields[2])) {
                throw new IllegalArgumentException("Invalid saved-query cursor.");
            }

            Instant updatedAt = Instant.parse(fields[3]);
            UUID savedQueryId = UUID.fromString(fields[4]);
            if (updatedAt.getNano() % 1_000 != 0 || !savedQueryId.toString().equals(fields[4])) {
                throw new IllegalArgumentException("Invalid saved-query cursor.");
            }
            return new Position(updatedAt, savedQueryId);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid saved-query cursor.", invalid);
        }
    }

    private static String searchFingerprint(String search) {
        String normalized = search == null ? "" : search.strip();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }

    record Position(Instant updatedAt, UUID savedQueryId) {
        Position {
            Objects.requireNonNull(updatedAt, "updatedAt");
            Objects.requireNonNull(savedQueryId, "savedQueryId");
        }
    }
}
