package com.neurosql.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Opaque, versioned keyset-pagination cursor scoped to one user, workspace,
 * and normalized outcome filter.
 *
 * <p>The cursor is navigation state, not an authorization credential. Every
 * history query still filters by the authenticated user and authorized
 * workspace in SQL.</p>
 */
final class QueryHistoryCursor {
    private static final String VERSION = "v2";
    private static final int MAX_ENCODED_LENGTH = 512;
    private static final Set<String> OUTCOMES = Set.of("SUCCEEDED", "FAILED", "CANCELED");
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private QueryHistoryCursor() {
    }

    static String encode(
            UUID workspaceId,
            UUID userId,
            Instant createdAt,
            UUID historyId,
            String outcome) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(historyId, "historyId");
        if (createdAt.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException("History timestamps must have PostgreSQL microsecond precision.");
        }

        String payload = String.join(
                "|",
                VERSION,
                workspaceId.toString(),
                userId.toString(),
                outcomeKey(outcome),
                createdAt.toString(),
                historyId.toString());
        String encoded = ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        if (encoded.length() > MAX_ENCODED_LENGTH) {
            throw new IllegalArgumentException("History cursor is too large.");
        }
        return encoded;
    }

    static Position decode(String encoded, UUID workspaceId, UUID userId, String outcome) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(userId, "userId");
        if (encoded == null || encoded.isBlank() || encoded.length() > MAX_ENCODED_LENGTH) {
            throw new IllegalArgumentException("Invalid history cursor.");
        }

        try {
            byte[] decoded = DECODER.decode(encoded);
            String payload = new String(decoded, StandardCharsets.UTF_8);
            if (!ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8)).equals(encoded)) {
                throw new IllegalArgumentException("Invalid history cursor.");
            }

            String[] fields = payload.split("\\|", -1);
            if (fields.length != 6
                    || !VERSION.equals(fields[0])
                    || !workspaceId.toString().equals(fields[1])
                    || !userId.toString().equals(fields[2])
                    || !outcomeKey(outcome).equals(fields[3])) {
                throw new IllegalArgumentException("Invalid history cursor.");
            }

            Instant createdAt = Instant.parse(fields[4]);
            UUID historyId = UUID.fromString(fields[5]);
            if (createdAt.getNano() % 1_000 != 0 || !historyId.toString().equals(fields[5])) {
                throw new IllegalArgumentException("Invalid history cursor.");
            }
            return new Position(createdAt, historyId);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid history cursor.", invalid);
        }
    }

    private static String outcomeKey(String outcome) {
        if (outcome == null) {
            return "ALL";
        }
        if (!OUTCOMES.contains(outcome)) {
            throw new IllegalArgumentException("Invalid history outcome filter.");
        }
        return outcome;
    }

    record Position(Instant createdAt, UUID historyId) {
        Position {
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(historyId, "historyId");
        }
    }
}
