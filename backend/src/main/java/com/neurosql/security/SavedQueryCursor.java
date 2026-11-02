package com.neurosql.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * Versioned keyset cursor for the shared saved-query list.
 *
 * <p>The cursor is navigation state, not an authorization credential. Every
 * repository query remains scoped to the workspace authorized by the request
 * filter.</p>
 */
final class SavedQueryCursor {
    private static final String VERSION = "v1";
    private static final int MAX_ENCODED_LENGTH = 384;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private SavedQueryCursor() {
    }

    static String encode(UUID workspaceId, Instant updatedAt, UUID savedQueryId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(savedQueryId, "savedQueryId");
        if (updatedAt.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException("Saved-query timestamps must have PostgreSQL microsecond precision.");
        }

        String payload = String.join(
                "|", VERSION, workspaceId.toString(), updatedAt.toString(), savedQueryId.toString());
        String encoded = ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        if (encoded.length() > MAX_ENCODED_LENGTH) {
            throw new IllegalArgumentException("Saved-query cursor is too large.");
        }
        return encoded;
    }

    static Position decode(String encoded, UUID workspaceId) {
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
            if (fields.length != 4
                    || !VERSION.equals(fields[0])
                    || !workspaceId.toString().equals(fields[1])) {
                throw new IllegalArgumentException("Invalid saved-query cursor.");
            }

            Instant updatedAt = Instant.parse(fields[2]);
            UUID savedQueryId = UUID.fromString(fields[3]);
            if (updatedAt.getNano() % 1_000 != 0 || !savedQueryId.toString().equals(fields[3])) {
                throw new IllegalArgumentException("Invalid saved-query cursor.");
            }
            return new Position(updatedAt, savedQueryId);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid saved-query cursor.", invalid);
        }
    }

    record Position(Instant updatedAt, UUID savedQueryId) {
        Position {
            Objects.requireNonNull(updatedAt, "updatedAt");
            Objects.requireNonNull(savedQueryId, "savedQueryId");
        }
    }
}
