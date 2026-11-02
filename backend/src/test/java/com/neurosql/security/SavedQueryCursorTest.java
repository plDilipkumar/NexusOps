package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class SavedQueryCursorTest {
    private static final UUID WORKSPACE_ID =
            UUID.fromString("b66c9e57-9e08-4b86-8f3f-65e8d9c60d6b");
    private static final UUID SAVED_QUERY_ID =
            UUID.fromString("4f3bd8a0-9172-4d40-8b58-0ec90adf7c4c");
    private static final Instant UPDATED_AT = Instant.parse("2026-11-02T10:30:00.123456Z");

    @Test
    void roundTripsCanonicalWorkspaceCursor() {
        String cursor = SavedQueryCursor.encode(WORKSPACE_ID, UPDATED_AT, SAVED_QUERY_ID);

        assertThat(SavedQueryCursor.decode(cursor, WORKSPACE_ID))
                .isEqualTo(new SavedQueryCursor.Position(UPDATED_AT, SAVED_QUERY_ID));
    }

    @Test
    void rejectsCursorReplayedAcrossWorkspaces() {
        String cursor = SavedQueryCursor.encode(WORKSPACE_ID, UPDATED_AT, SAVED_QUERY_ID);

        assertThatThrownBy(() -> SavedQueryCursor.decode(cursor, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMalformedAndNonCanonicalCursors() {
        String cursor = SavedQueryCursor.encode(WORKSPACE_ID, UPDATED_AT, SAVED_QUERY_ID);

        assertThatThrownBy(() -> SavedQueryCursor.decode(cursor + "=", WORKSPACE_ID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SavedQueryCursor.decode("not-a-cursor", WORKSPACE_ID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SavedQueryCursor.decode(" ", WORKSPACE_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTimestampsWithoutPostgresMicrosecondPrecision() {
        assertThatThrownBy(() -> SavedQueryCursor.encode(
                WORKSPACE_ID,
                Instant.parse("2026-11-02T10:30:00.123456789Z"),
                SAVED_QUERY_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
