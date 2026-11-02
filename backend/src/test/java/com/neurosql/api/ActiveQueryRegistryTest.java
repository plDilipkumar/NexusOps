package com.neurosql.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ActiveQueryRegistryTest {
    private static final UUID EXECUTION_ID = UUID.fromString("dc1bd6a0-9fd6-49d9-a65f-b4e8c6b08e1b");
    private static final UUID USER_ID = UUID.fromString("b4f41d45-68dc-4f7b-8df0-48e3f29e54c1");
    private static final UUID OTHER_USER_ID = UUID.fromString("a2a5b2cb-1d53-4f9d-b2a8-bcdb7b8f2b0f");
    private static final UUID WORKSPACE_ID = UUID.fromString("b66c9e57-9e08-4b86-8f3f-65e8d9c60d6b");
    private static final UUID OTHER_WORKSPACE_ID = UUID.fromString("b6a8d11d-f9bd-46d9-8d84-1b90d31a5d28");

    private final ActiveQueryRegistry registry = new ActiveQueryRegistry();

    @Test
    void cancellationRequiresTheExactUserAndWorkspace() throws Exception {
        ActiveQueryRegistry.Handle handle = registry.begin(EXECUTION_ID, USER_ID, WORKSPACE_ID);
        PreparedStatement statement = mock(PreparedStatement.class);
        handle.attachStatement(statement);

        assertThat(registry.cancel(EXECUTION_ID, OTHER_USER_ID, WORKSPACE_ID))
                .isEqualTo(ActiveQueryRegistry.CancelResult.NOT_FOUND);
        assertThat(registry.cancel(EXECUTION_ID, USER_ID, OTHER_WORKSPACE_ID))
                .isEqualTo(ActiveQueryRegistry.CancelResult.NOT_FOUND);
        verifyNoInteractions(statement);

        assertThat(registry.cancel(EXECUTION_ID, USER_ID, WORKSPACE_ID))
                .isEqualTo(ActiveQueryRegistry.CancelResult.REQUESTED);
        verify(statement).cancel();

        handle.close();
        assertThat(registry.activeCount()).isZero();
        assertThat(registry.cancel(EXECUTION_ID, USER_ID, WORKSPACE_ID))
                .isEqualTo(ActiveQueryRegistry.CancelResult.NOT_FOUND);
    }

    @Test
    void cancellationBeforeStatementAttachmentPreventsExecution() throws Exception {
        ActiveQueryRegistry.Handle handle = registry.begin(EXECUTION_ID, USER_ID, WORKSPACE_ID);
        PreparedStatement statement = mock(PreparedStatement.class);

        assertThat(registry.cancel(EXECUTION_ID, USER_ID, WORKSPACE_ID))
                .isEqualTo(ActiveQueryRegistry.CancelResult.REQUESTED);
        assertThatThrownBy(() -> handle.attachStatement(statement))
                .isInstanceOf(SQLException.class)
                .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("57014"));
        verify(statement).close();

        handle.close();
        assertThat(registry.activeCount()).isZero();
    }

    @Test
    void duplicateIdentifiersAndExcessiveConcurrentQueriesAreRejected() {
        ActiveQueryRegistry.Handle first = registry.begin(EXECUTION_ID, USER_ID, WORKSPACE_ID);
        assertThatThrownBy(() -> registry.begin(EXECUTION_ID, USER_ID, WORKSPACE_ID))
                .isInstanceOf(ActiveQueryRegistry.DuplicateExecutionException.class);
        first.close();

        List<ActiveQueryRegistry.Handle> handles = new ArrayList<>();
        try {
            for (int index = 0; index < 64; index++) {
                handles.add(registry.begin(UUID.randomUUID(), USER_ID, WORKSPACE_ID));
            }
            assertThat(registry.activeCount()).isEqualTo(64);
            assertThatThrownBy(() -> registry.begin(UUID.randomUUID(), USER_ID, WORKSPACE_ID))
                    .isInstanceOf(ActiveQueryRegistry.CapacityExceededException.class);
        } finally {
            handles.forEach(ActiveQueryRegistry.Handle::close);
        }
        assertThat(registry.activeCount()).isZero();
    }
}
