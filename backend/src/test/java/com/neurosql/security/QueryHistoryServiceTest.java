package com.neurosql.security;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.eq;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class QueryHistoryServiceTest {
    private static final UUID USER_ID = UUID.fromString("b4f41d45-68dc-4f7b-8df0-48e3f29e54c1");
    private static final UUID WORKSPACE_ID = UUID.fromString("b66c9e57-9e08-4b86-8f3f-65e8d9c60d6b");

    private final JdbcQueryHistoryRepository repository = mock(JdbcQueryHistoryRepository.class);
    private final QueryHistoryService service = new QueryHistoryService(repository);

    @Test
    void recordsSuccessfulExecutionWithTrimmedSqlAndSha256Fingerprint() {
        service.recordSucceeded(USER_ID, WORKSPACE_ID, "  SELECT 1  ", 1, 42, true);

        verify(repository).append(
                eq(WORKSPACE_ID),
                eq(USER_ID),
                eq("SELECT 1"),
                eq(QueryHistoryService.sha256("SELECT 1")),
                eq("SUCCEEDED"),
                eq(1),
                eq(42),
                eq(true));
    }

    @Test
    void recordsFailedExecutionWithoutInventingARowCount() {
        service.recordFailed(USER_ID, WORKSPACE_ID, "SELECT broken FROM", 31_000);

        verify(repository).append(
                eq(WORKSPACE_ID),
                eq(USER_ID),
                eq("SELECT broken FROM"),
                eq(QueryHistoryService.sha256("SELECT broken FROM")),
                eq("FAILED"),
                eq(null),
                eq(30_000),
                eq(false));
    }

    @Test
    void recordsCancelledExecutionWithoutInventingARowCount() {
        service.recordCanceled(USER_ID, WORKSPACE_ID, "SELECT pg_sleep(20)", 812);

        verify(repository).append(
                eq(WORKSPACE_ID),
                eq(USER_ID),
                eq("SELECT pg_sleep(20)"),
                eq(QueryHistoryService.sha256("SELECT pg_sleep(20)")),
                eq("CANCELED"),
                eq(null),
                eq(812),
                eq(false));
    }

    @Test
    void historyWriteFailureDoesNotChangeQueryExecutionOutcome() {
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("database detail"))
                .when(repository).append(
                        eq(WORKSPACE_ID),
                        eq(USER_ID),
                        eq("SELECT 1"),
                        eq(QueryHistoryService.sha256("SELECT 1")),
                        eq("SUCCEEDED"),
                        eq(1),
                        eq(3),
                        eq(false));

        service.recordSucceeded(USER_ID, WORKSPACE_ID, "SELECT 1", 1, 3, false);

        verify(repository).append(
                eq(WORKSPACE_ID),
                eq(USER_ID),
                eq("SELECT 1"),
                eq(QueryHistoryService.sha256("SELECT 1")),
                eq("SUCCEEDED"),
                eq(1),
                eq(3),
                eq(false));
    }

    @Test
    void rejectsBlankOrOversizedSqlBeforeDatabaseAccess() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> service.recordSucceeded(USER_ID, WORKSPACE_ID, "  ", 0, 1, false));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> service.recordSucceeded(USER_ID, WORKSPACE_ID, "x".repeat(50_001), 0, 1, false));
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsNegativeRowCounts() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> service.recordSucceeded(USER_ID, WORKSPACE_ID, "SELECT 1", -1, 1, false));
        verify(repository, never()).append(
                eq(WORKSPACE_ID), eq(USER_ID), eq("SELECT 1"),
                eq(QueryHistoryService.sha256("SELECT 1")),
                eq("SUCCEEDED"), eq(-1), eq(1), eq(false));
    }
}
