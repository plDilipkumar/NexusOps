package com.neurosql.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.neurosql.security.DurableSessionAuthenticationFilter;
import com.neurosql.security.WorkspaceAuthorizationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class DurableSqlExplainCancellationTest {
    private static final UUID USER_ID = UUID.fromString("b4f41d45-68dc-4f7b-8df0-48e3f29e54c1");
    private static final UUID SESSION_ID = UUID.fromString("4cfa688e-8890-4b5e-8a9a-19f074d5a1f3");
    private static final UUID WORKSPACE_ID = UUID.fromString("b66c9e57-9e08-4b86-8f3f-65e8d9c60d6b");

    @Test
    void sessionRevocationCancelsAnInFlightExplainAndSuppressesThePlan() throws Exception {
        ReadOnlyQueryExecutor executor = mock(ReadOnlyQueryExecutor.class);
        ActiveQueryRegistry registry = new ActiveQueryRegistry();
        CountDownLatch statementAttached = new CountDownLatch(1);
        AtomicReference<PreparedStatement> attachedStatement = new AtomicReference<>();

        when(executor.explain(eq("SELECT 1"), any(ActiveQueryRegistry.Handle.class)))
                .thenAnswer(invocation -> {
                    ActiveQueryRegistry.Handle handle = invocation.getArgument(1);
                    PreparedStatement statement = mock(PreparedStatement.class);
                    handle.attachStatement(statement);
                    attachedStatement.set(statement);
                    statementAttached.countDown();

                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    while (!handle.cancellationRequested() && System.nanoTime() < deadline) {
                        Thread.sleep(5);
                    }
                    if (!handle.cancellationRequested()) {
                        throw new AssertionError("The EXPLAIN request was not cancelled with its durable session.");
                    }
                    throw new ReadOnlyQueryExecutor.QueryCancelledException();
                });

        SqlExecutionController controller = new SqlExecutionController(executor, null, registry);
        DurableSessionAuthenticationFilter.SessionPrincipal principal =
                new DurableSessionAuthenticationFilter.SessionPrincipal(
                        USER_ID, SESSION_ID, Instant.now().plusSeconds(3600));
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, List.of());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(WorkspaceAuthorizationFilter.WORKSPACE_REQUEST_ATTRIBUTE, WORKSPACE_ID);

        ExecutorService requestThread = Executors.newSingleThreadExecutor();
        Future<ResponseEntity<?>> response = requestThread.submit(() -> controller.explain(
                new SqlExecutionController.QueryRequest("SELECT 1"),
                authentication,
                request));

        try {
            assertThat(statementAttached.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(registry.activeCount()).isEqualTo(1);
            assertThat(registry.cancelSession(SESSION_ID)).isEqualTo(1);

            ResponseEntity<?> cancelled = response.get(3, TimeUnit.SECONDS);
            assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(cancelled.getBody()).isInstanceOf(SqlExecutionController.QueryError.class);
            assertThat(((SqlExecutionController.QueryError) cancelled.getBody()).message())
                    .isEqualTo("The EXPLAIN request was cancelled.");
            verify(attachedStatement.get()).cancel();
            assertThat(registry.activeCount()).isZero();
        } finally {
            response.cancel(true);
            requestThread.shutdownNow();
            requestThread.awaitTermination(2, TimeUnit.SECONDS);
        }
    }
}
