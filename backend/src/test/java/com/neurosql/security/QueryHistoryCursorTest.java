package com.neurosql.security;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QueryHistoryCursorTest {
    private static final UUID WORKSPACE_ID=UUID.fromString("b66c9e57-9e08-4b86-8f3f-65e8d9c60d6b");
    private static final UUID USER_ID=UUID.fromString("b4f41d45-68dc-4f7b-8df0-48e3f29e54c1");
    private static final UUID HISTORY_ID=UUID.fromString("c5f52e56-79ed-4c80-9e50-48e3d9d4e1a7");
    private static final Instant CREATED_AT=Instant.parse("2026-11-02T10:15:30.123456Z");
    private static final LocalDate FROM=LocalDate.parse("2026-11-01"),TO=LocalDate.parse("2026-11-02");

    @Test void roundTripsTimestampAndAllFilterScope() {
        String encoded=QueryHistoryCursor.encode(WORKSPACE_ID,USER_ID,CREATED_AT,HISTORY_ID,"FAILED",FROM,TO);
        var decoded=QueryHistoryCursor.decode(encoded,WORKSPACE_ID,USER_ID,"FAILED",FROM,TO);
        assertThat(decoded.createdAt()).isEqualTo(CREATED_AT);
        assertThat(decoded.historyId()).isEqualTo(HISTORY_ID);
    }

    @Test void rejectsMalformedAndNonCanonicalCursors() {
        assertThatThrownBy(()->QueryHistoryCursor.decode("not-a-cursor",WORKSPACE_ID,USER_ID,null,null,null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid history cursor.");
        String padded=QueryHistoryCursor.encode(WORKSPACE_ID,USER_ID,CREATED_AT,HISTORY_ID,null,null,null)+"=";
        assertThatThrownBy(()->QueryHistoryCursor.decode(padded,WORKSPACE_ID,USER_ID,null,null,null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsReplayAcrossUsersWorkspacesOutcomesOrDateRanges() {
        String encoded=QueryHistoryCursor.encode(WORKSPACE_ID,USER_ID,CREATED_AT,HISTORY_ID,"FAILED",FROM,TO);
        assertThatThrownBy(()->QueryHistoryCursor.decode(encoded,UUID.randomUUID(),USER_ID,"FAILED",FROM,TO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->QueryHistoryCursor.decode(encoded,WORKSPACE_ID,UUID.randomUUID(),"FAILED",FROM,TO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->QueryHistoryCursor.decode(encoded,WORKSPACE_ID,USER_ID,"SUCCEEDED",FROM,TO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->QueryHistoryCursor.decode(encoded,WORKSPACE_ID,USER_ID,"FAILED",TO,TO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->QueryHistoryCursor.decode(encoded,WORKSPACE_ID,USER_ID,"FAILED",FROM,null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsOversizedCursorsInvalidPrecisionUnknownOutcomeAndReversedDates() {
        assertThatThrownBy(()->QueryHistoryCursor.decode("a".repeat(513),WORKSPACE_ID,USER_ID,null,null,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->QueryHistoryCursor.encode(WORKSPACE_ID,USER_ID,Instant.parse("2026-11-02T10:15:30.123456789Z"),HISTORY_ID,null,null,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->QueryHistoryCursor.encode(WORKSPACE_ID,USER_ID,CREATED_AT,HISTORY_ID,"DELETE",null,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->QueryHistoryCursor.encode(WORKSPACE_ID,USER_ID,CREATED_AT,HISTORY_ID,null,TO,FROM)).isInstanceOf(IllegalArgumentException.class);
    }
}
