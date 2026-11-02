package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class SavedQueryServiceTest {
    private static final UUID WORKSPACE_ID =
            UUID.fromString("b66c9e57-9e08-4b86-8f3f-65e8d9c60d6b");
    private static final UUID USER_ID =
            UUID.fromString("b4f41d45-68dc-4f7b-8df0-48e3f29e54c1");
    private static final Instant UPDATED_AT = Instant.parse("2026-11-02T10:30:00.123456Z");

    @Mock
    private JdbcSavedQueryRepository repository;

    private SavedQueryService service;

    @BeforeEach
    void setUp() {
        service = new SavedQueryService(repository);
    }

    @Test
    void createTrimsNameDescriptionAndSqlBeforePersistence() {
        var entry = entry(UUID.randomUUID(), "Monthly revenue", "Finance report", "SELECT 1");
        when(repository.insert(
                WORKSPACE_ID, USER_ID, "Monthly revenue", "Finance report", "SELECT 1"))
                .thenReturn(entry);

        assertThat(service.create(
                WORKSPACE_ID, USER_ID, "  Monthly revenue  ", " Finance report ", "\n SELECT 1 \n"))
                .isEqualTo(entry);

        verify(repository).insert(
                WORKSPACE_ID, USER_ID, "Monthly revenue", "Finance report", "SELECT 1");
    }

    @Test
    void emptyDescriptionIsNormalizedToAnEmptyString() {
        var entry = entry(UUID.randomUUID(), "Basic report", "", "SELECT 1");
        when(repository.insert(WORKSPACE_ID, USER_ID, "Basic report", "", "SELECT 1"))
                .thenReturn(entry);

        assertThat(service.create(WORKSPACE_ID, USER_ID, "Basic report", null, "SELECT 1"))
                .isEqualTo(entry);
    }

    @Test
    void rejectsInvalidNameDescriptionAndSqlWithoutDatabaseAccess() {
        assertThatThrownBy(() -> service.create(WORKSPACE_ID, USER_ID, "  ", "", "SELECT 1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(WORKSPACE_ID, USER_ID, "bad\nname", "", "SELECT 1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(WORKSPACE_ID, USER_ID, "Valid", "x".repeat(501), "SELECT 1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(WORKSPACE_ID, USER_ID, "Valid", "", "  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(WORKSPACE_ID, USER_ID, "Valid", "", "x".repeat(50_001)))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void mapsCaseInsensitiveWorkspaceNameConflictToDomainConflict() {
        when(repository.insert(WORKSPACE_ID, USER_ID, "Monthly", "", "SELECT 1"))
                .thenThrow(new DuplicateKeyException("unique index"));

        assertThatThrownBy(() -> service.create(WORKSPACE_ID, USER_ID, "Monthly", "", "SELECT 1"))
                .isInstanceOf(SavedQueryService.DuplicateNameException.class);
    }

    @Test
    void listUsesBoundedKeysetPaginationAndReturnsNextCursor() {
        var first = entry(UUID.fromString("10000000-0000-4000-8000-000000000001"), "One", "", "SELECT 1");
        var second = entry(UUID.fromString("10000000-0000-4000-8000-000000000002"), "Two", "", "SELECT 2");
        var third = entry(UUID.fromString("10000000-0000-4000-8000-000000000003"), "Three", "", "SELECT 3");
        when(repository.findRecent(WORKSPACE_ID, 3, null)).thenReturn(List.of(first, second, third));

        SavedQueryService.SavedQueryPage page = service.list(WORKSPACE_ID, 2, null);

        assertThat(page.items()).containsExactly(first, second);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isNotBlank();
        assertThat(SavedQueryCursor.decode(page.nextCursor(), WORKSPACE_ID))
                .isEqualTo(new SavedQueryCursor.Position(UPDATED_AT, second.savedQueryId()));
        verify(repository).findRecent(WORKSPACE_ID, 3, null);
    }

    @Test
    void rejectsInvalidPageSizeAndCrossWorkspaceCursorBeforeReading() {
        assertThatThrownBy(() -> service.list(WORKSPACE_ID, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list(WORKSPACE_ID, 101, null))
                .isInstanceOf(IllegalArgumentException.class);

        String cursor = SavedQueryCursor.encode(
                UUID.randomUUID(), UPDATED_AT, UUID.randomUUID());
        assertThatThrownBy(() -> service.list(WORKSPACE_ID, 25, cursor))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    private static JdbcSavedQueryRepository.SavedQueryEntry entry(
            UUID id, String name, String description, String queryText) {
        return new JdbcSavedQueryRepository.SavedQueryEntry(
                id, WORKSPACE_ID, USER_ID, name, description, queryText, UPDATED_AT, UPDATED_AT);
    }
}
