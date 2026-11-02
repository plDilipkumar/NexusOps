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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class QueryHistoryReadServiceTest {
    private static final UUID WORKSPACE_ID = UUID.fromString("b66c9e57-9e08-4b86-8f3f-65e8d9c60d6b");
    private static final UUID USER_ID = UUID.fromString("b4f41d45-68dc-4f7b-8df0-48e3f29e54c1");
    private final JdbcQueryHistoryRepository repository = mock(JdbcQueryHistoryRepository.class);
    private final QueryHistoryReadService service = new QueryHistoryReadService(repository);

    @Test void returnsBoundedPageAndCursorFromLastVisibleRowWhenMoreRowsExist() {
        var newest=entry("SELECT 3","2026-11-02T10:03:00Z","SUCCEEDED");
        var second=entry("SELECT 2","2026-11-02T10:02:00Z","SUCCEEDED");
        var oldest=entry("SELECT 1","2026-11-02T10:01:00Z","SUCCEEDED");
        when(repository.findRecent(eq(WORKSPACE_ID),eq(USER_ID),eq(3),isNull(),isNull(),isNull(),isNull(),isNull()))
                .thenReturn(List.of(newest,second,oldest));
        var page=service.list(USER_ID,WORKSPACE_ID,2,null,null,null,null);
        assertThat(page.items()).containsExactly(newest,second);
        assertThat(page.hasMore()).isTrue();
        assertThat(QueryHistoryCursor.decode(page.nextCursor(),WORKSPACE_ID,USER_ID,null,null,null).historyId()).isEqualTo(second.historyId());
        verify(repository).findRecent(eq(WORKSPACE_ID),eq(USER_ID),eq(3),isNull(),isNull(),isNull(),isNull(),isNull());
    }

    @Test void omitsNextCursorWhenThePageIsExhausted() {
        var only=entry("SELECT 1","2026-11-02T10:01:00Z","SUCCEEDED");
        when(repository.findRecent(eq(WORKSPACE_ID),eq(USER_ID),eq(3),isNull(),isNull(),isNull(),isNull(),isNull())).thenReturn(List.of(only));
        var page=service.list(USER_ID,WORKSPACE_ID,2,null,null,null,null);
        assertThat(page.items()).containsExactly(only);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test void decodesAndPassesScopedKeysetPositionToRepository() {
        var item=entry("SELECT 1","2026-11-02T10:01:00Z","FAILED");
        var position=new QueryHistoryCursor.Position(item.createdAt(),item.historyId());
        String cursor=QueryHistoryCursor.encode(WORKSPACE_ID,USER_ID,position.createdAt(),position.historyId(),"FAILED",null,null);
        when(repository.findRecent(eq(WORKSPACE_ID),eq(USER_ID),eq(11),eq(position),eq("FAILED"),isNull(),isNull(),isNull())).thenReturn(List.of());
        service.list(USER_ID,WORKSPACE_ID,10,cursor,"failed",null,null);
        verify(repository).findRecent(eq(WORKSPACE_ID),eq(USER_ID),eq(11),eq(position),eq("FAILED"),isNull(),isNull(),isNull());
    }

    @Test void bindsPaginationToNormalizedOutcomeAndRejectsCursorReplayAcrossFilters() {
        var failed=entry("SELECT broken","2026-11-02T10:01:00Z","FAILED");
        var second=entry("SELECT broken 2","2026-11-02T10:00:00Z","FAILED");
        when(repository.findRecent(eq(WORKSPACE_ID),eq(USER_ID),eq(2),isNull(),eq("FAILED"),isNull(),isNull(),isNull())).thenReturn(List.of(failed,second));
        var page=service.list(USER_ID,WORKSPACE_ID,1,null," failed ",null,null);
        assertThat(page.items()).containsExactly(failed);
        assertThat(QueryHistoryCursor.decode(page.nextCursor(),WORKSPACE_ID,USER_ID,"FAILED",null,null).historyId()).isEqualTo(failed.historyId());
        assertThatThrownBy(()->service.list(USER_ID,WORKSPACE_ID,1,page.nextCursor(),"SUCCEEDED",null,null)).isInstanceOf(IllegalArgumentException.class);
        verify(repository).findRecent(eq(WORKSPACE_ID),eq(USER_ID),eq(2),isNull(),eq("FAILED"),isNull(),isNull(),isNull());
    }

    @Test void bindsInclusiveUtcDateRangeToRepositoryAndCursor() {
        LocalDate from=LocalDate.parse("2026-11-01"),to=LocalDate.parse("2026-11-02");
        var first=entry("SELECT first","2026-11-02T10:01:00Z","FAILED");
        var second=entry("SELECT second","2026-11-02T10:00:00Z","FAILED");
        when(repository.findRecent(eq(WORKSPACE_ID),eq(USER_ID),eq(2),isNull(),eq("FAILED"),eq(from),eq(to),isNull())).thenReturn(List.of(first,second));
        var page=service.list(USER_ID,WORKSPACE_ID,1,null," failed ","2026-11-01","2026-11-02");
        assertThat(page.items()).containsExactly(first);
        assertThat(QueryHistoryCursor.decode(page.nextCursor(),WORKSPACE_ID,USER_ID,"FAILED",from,to).historyId()).isEqualTo(first.historyId());
        assertThatThrownBy(()->service.list(USER_ID,WORKSPACE_ID,1,page.nextCursor(),"FAILED","2026-11-02","2026-11-02")).isInstanceOf(IllegalArgumentException.class);
        verify(repository).findRecent(eq(WORKSPACE_ID),eq(USER_ID),eq(2),isNull(),eq("FAILED"),eq(from),eq(to),isNull());
    }

    @Test void bindsCaseNormalizedSqlSearchToRepositoryAndCursor() {
        var first=entry("SELECT * FROM finance_orders","2026-11-02T10:01:00Z","SUCCEEDED");
        var second=entry("SELECT * FROM finance_orders_archive","2026-11-02T10:00:00Z","SUCCEEDED");
        when(repository.findRecent(eq(WORKSPACE_ID),eq(USER_ID),eq(2),isNull(),isNull(),isNull(),isNull(),eq("select finance_orders")))
                .thenReturn(List.of(first,second));
        var page=service.list(USER_ID,WORKSPACE_ID,1,null,null,null,null,"  SELECT FINANCE_ORDERS  ");
        assertThat(page.items()).containsExactly(first);
        assertThat(QueryHistoryCursor.decode(page.nextCursor(),WORKSPACE_ID,USER_ID,null,null,null,"select finance_orders").historyId())
                .isEqualTo(first.historyId());
        assertThatThrownBy(()->service.list(USER_ID,WORKSPACE_ID,1,page.nextCursor(),null,null,null,"finance_accounts"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository).findRecent(eq(WORKSPACE_ID),eq(USER_ID),eq(2),isNull(),isNull(),isNull(),isNull(),eq("select finance_orders"));
    }

    @Test void rejectsInvalidSearchLengthAndControlCharactersBeforeDatabaseAccess() {
        assertThatThrownBy(()->service.list(USER_ID,WORKSPACE_ID,25,null,null,null,null,"x".repeat(121)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.list(USER_ID,WORKSPACE_ID,25,null,null,null,null,"select" + (char) 0 + "users"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.list(USER_ID,WORKSPACE_ID,25,null,null,null,null," ".repeat(500)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test void rejectsInvalidDatesReversedRangesAndInvalidPageSizesBeforeDatabaseAccess() {
        assertThatThrownBy(()->service.list(USER_ID,WORKSPACE_ID,25,null,null,"2026-02-30",null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.list(USER_ID,WORKSPACE_ID,25,null,null,"2026-11-03","2026-11-02")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.list(USER_ID,WORKSPACE_ID,25,null,null,"2026-11-02x",null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.list(USER_ID,WORKSPACE_ID,0,null,null,null,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.list(USER_ID,WORKSPACE_ID,101,null,null,null,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.list(USER_ID,WORKSPACE_ID,25,null,"DROP",null,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.list(USER_ID,WORKSPACE_ID,25,null,"x".repeat(17),null,null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test void rejectsCrossScopeCursorBeforeDatabaseAccess() {
        String cursor=QueryHistoryCursor.encode(WORKSPACE_ID,USER_ID,Instant.parse("2026-11-02T10:01:00Z"),UUID.randomUUID(),null,null,null);
        assertThatThrownBy(()->service.list(UUID.randomUUID(),WORKSPACE_ID,25,cursor,null,null,null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    private static JdbcQueryHistoryRepository.HistoryEntry entry(String sql,String createdAt,String outcome) {
        Integer rowCount="SUCCEEDED".equals(outcome)?1:null;
        return new JdbcQueryHistoryRepository.HistoryEntry(UUID.randomUUID(),sql,outcome,rowCount,4,false,Instant.parse(createdAt));
    }
}
