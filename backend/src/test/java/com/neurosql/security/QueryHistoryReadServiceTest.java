package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class QueryHistoryReadServiceTest {
    private static final UUID WORKSPACE_ID =
            UUID.fromString("b66c9e57-9e08-4b86-8f3f-65e8d9c60d6b");
    private static final UUID USER_ID =
            UUID.fromString("b4f41d45-68dc-4f7b-8df0-48e3f29e54c1");

    private final JdbcQueryHistoryRepository repository = mock(JdbcQueryHistoryRepository.class);
    private final QueryHistoryReadService service = new QueryHistoryReadService(repository);

    @Test
    void returnsBoundedPageAndCursorFromLastVisibleRowWhenMoreRowsExist() {
        var newest = entry("SELECT 3", "2026-11-02T10:03:00Z", "SUCCEEDED");
        var second = entry("SELECT 2", "2026-11-02T10:02:00Z", "SUCCEEDED");
        var oldest = entry("SELECT 1", "2026-11-02T10:01:00Z", "SUCCEEDED");
        when(repository.findRecent(eq(WORKSPACE_ID), eq(USER_ID), eq(3), isNull(), isNull()))
                .thenReturn(List.of(newest, second, oldest));

        QueryHistoryReadService.QueryHistoryPage page =
                service.list(USER_ID, WORKSPACE_ID, 2, null, null);

        assertThat(page.items()).containsExactly(newest, second);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isNotBlank();
        assertThat(QueryHistoryCursor.decode(page.nextCursor(), WORKSPACE_ID, USER_ID, null).historyId())
                .isEqualTo(second.historyId());
        verify(repository).findRecent(eq(WORKSPACE_ID), eq(USER_ID), eq(3), isNull(), isNull());
    }

    @Test
    void omitsNextCursorWhenThePageIsExhausted() {
        var only = entry("SELECT 1", "2026-11-02T10:01:00Z", "SUCCEEDED");
        when(repository.findRecent(eq(WORKSPACE_ID), eq(USER_ID), eq(3), isNull(), isNull()))
                .thenReturn(List.of(only));

        QueryHistoryReadService.QueryHistoryPage page =
                service.list(USER_ID, WORKSPACE_ID, 2, null, null);

        assertThat(page.items()).containsExactly(only);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void decodesAndPassesScopedKeysetPositionToRepository() {
        var positionEntry = entry("SELECT 1", "2026-11-02T10:01:00Z", "FAILED");
        QueryHistoryCursor.Position position =
                new QueryHistoryCursor.Position(positionEntry.createdAt(), positionEntry.historyId());
        String cursor = QueryHistoryCursor.encode(
                WORKSPACE_ID, USER_ID, position.createdAt(), position.historyId(), "FAILED");
        when(repository.findRecent(eq(WORKSPACE_ID), eq(USER_ID), eq(11), eq(position), eq("FAILED")))
                .thenReturn(List.of());

        QueryHistoryReadService.QueryHistoryPage page =
                service.list(USER_ID, WORKSPACE_ID, 10, cursor, "failed");

        assertThat(page.items()).isEmpty();
        verify(repository).findRecent(eq(WORKSPACE_ID), eq(USER_ID), eq(11), eq(position), eq("FAILED"));
    }

    @Test
    void bindsPaginationToNormalizedOutcomeAndRejectsCursorReplayAcrossFilters() {
        var failed = entry("SELECT broken", "2026-11-02T10:01:00Z", "FAILED");
        var second = entry("SELECT broken 2", "2026-11-02T10:00:00Z", "FAILED");
        when(repository.findRecent(eq(WORKSPACE_ID), eq(USER_ID), eq(2), isNull(), eq("FAILED")))
                .thenReturn(List.of(failed, second));

        QueryHistoryReadService.QueryHistoryPage page =
                service.list(USER_ID, WORKSPACE_ID, 1, null, " failed ");

        assertThat(page.items()).containsExactly(failed);
        assertThat(page.hasMore()).isTrue();
        assertThat(QueryHistoryCursor.decode(page.nextCursor(), WORKSPACE_ID, USER_ID, "FAILED").historyId())
                .isEqualTo(failed.historyId());
        assertThatThrownBy(() -> service.list(
                USER_ID, WORKSPACE_ID, 1, page.nextCursor(), "SUCCEEDED"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository).findRecent(eq(WORKSPACE_ID), eq(USER_ID), eq(2), isNull(), eq("FAILED"));
    }

    @Test
    void rejectsInvalidOutcomeAndPageSizeBeforeDatabaseAccess() {
        assertThatThrownBy(() -> service.list(USER_ID, WORKSPACE_ID, 0, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list(USER_ID, WORKSPACE_ID, 101, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list(USER_ID, WORKSPACE_ID, 25, null, "DROP"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list(USER_ID, WORKSPACE_ID, 25, null, "x".repeat(17)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsCrossScopeCursorBeforeDatabaseAccess() {
        String cursor = QueryHistoryCursor.encode(
                WORKSPACE_ID, USER_ID, Instant.parse("2026-11-02T10:01:00Z"), UUID.randomUUID(), null);
        assertThatThrownBy(() -> service.list(
                UUID.randomUUID(), WORKSPACE_ID, 25, cursor, null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    private static JdbcQueryHistoryRepository.HistoryEntry entry(
            String sql, String createdAt, String outcome) {
        Integer rowCount = "SUCCEEDED".equals(outcome) ? 1 : null;
        return new JdbcQueryHistoryRepository.HistoryEntry(
                UUID.randomUUID(), sql, outcome, rowCount, 4, false, Instant.parse(createdAt));
    }
}
