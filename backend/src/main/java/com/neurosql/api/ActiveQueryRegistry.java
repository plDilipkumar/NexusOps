package com.neurosql.api;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks only currently executing durable-session SQL statements so their
 * owning user can request JDBC cancellation. Entries are process-local and
 * bounded; they are never used as an authorization substitute.
 */
public final class ActiveQueryRegistry {
    public static final String EXECUTION_ID_HEADER = "X-NeuroSQL-Execution-Id";
    private static final int MAX_ACTIVE_QUERIES = 64;

    private final ConcurrentHashMap<UUID, ActiveQuery> activeQueries = new ConcurrentHashMap<>();
    private final Semaphore capacity = new Semaphore(MAX_ACTIVE_QUERIES);

    /** Compatibility overload for registry-only callers without a durable session. */
    public Handle begin(UUID executionId, UUID userId, UUID workspaceId) {
        return begin(executionId, userId, null, workspaceId);
    }

    public Handle begin(UUID executionId, UUID userId, UUID sessionId, UUID workspaceId) {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        if (!capacity.tryAcquire()) {
            throw new CapacityExceededException();
        }
        ActiveQuery query = new ActiveQuery(executionId, userId, sessionId, workspaceId);
        if (activeQueries.putIfAbsent(executionId, query) != null) {
            capacity.release();
            throw new DuplicateExecutionException();
        }
        return new Handle(this, query);
    }

    /** Compatibility overload for registry-only callers without a durable session. */
    public CancelResult cancel(UUID executionId, UUID userId, UUID workspaceId) {
        return cancel(executionId, userId, null, workspaceId);
    }

    public CancelResult cancel(UUID executionId, UUID userId, UUID sessionId, UUID workspaceId) {
        if (executionId == null || userId == null || workspaceId == null) {
            return CancelResult.NOT_FOUND;
        }
        ActiveQuery query = activeQueries.get(executionId);
        if (query == null) {
            return CancelResult.NOT_FOUND;
        }

        PreparedStatement statement;
        synchronized (query) {
            if (query.completed || activeQueries.get(executionId) != query
                    || !query.userId.equals(userId)
                    || !Objects.equals(query.sessionId, sessionId)
                    || !query.workspaceId.equals(workspaceId)) {
                return CancelResult.NOT_FOUND;
            }
            if (query.cancelRequested) {
                return CancelResult.ALREADY_REQUESTED;
            }
            query.cancelRequested = true;
            statement = query.statement;
        }
        if (statement == null) {
            // attachStatement stops the query if cancellation arrived before JDBC setup.
            return CancelResult.REQUESTED;
        }
        try {
            statement.cancel();
            return CancelResult.REQUESTED;
        } catch (SQLException cancellationFailure) {
            synchronized (query) {
                if (!query.completed && activeQueries.get(executionId) == query) {
                    query.cancelRequested = false;
                }
            }
            return CancelResult.FAILED;
        }
    }

    /**
     * Cancels every active query owned by a revoked session. This is an
     * internal revocation hook, not an HTTP cancellation path. The cancellation
     * flag remains set even if JDBC cancellation fails so the request handler
     * can suppress a result that completes after revocation.
     */
    public int cancelSession(UUID sessionId) {
        if (sessionId == null) {
            return 0;
        }

        int requested = 0;
        for (ActiveQuery query : activeQueries.values()) {
            PreparedStatement statement;
            synchronized (query) {
                if (query.completed
                        || activeQueries.get(query.executionId) != query
                        || !sessionId.equals(query.sessionId)
                        || query.cancelRequested) {
                    continue;
                }
                query.cancelRequested = true;
                statement = query.statement;
            }

            if (statement != null) {
                try {
                    statement.cancel();
                } catch (SQLException ignored) {
                    // Keep the cancellation flag set; do not expose driver details.
                }
            }
            requested++;
        }
        return requested;
    }

    public int activeCount() {
        return activeQueries.size();
    }

    private void attachStatement(ActiveQuery query, PreparedStatement statement) throws SQLException {
        synchronized (query) {
            if (query.completed || activeQueries.get(query.executionId) != query || query.cancelRequested) {
                closeQuietly(statement);
                throw new SQLException("The query was cancelled before execution.", "57014");
            }
            query.statement = statement;
        }
    }

    private boolean cancellationRequested(ActiveQuery query) {
        synchronized (query) {
            return query.cancelRequested;
        }
    }

    private void finish(ActiveQuery query) {
        boolean releaseCapacity = false;
        synchronized (query) {
            if (!query.completed) {
                query.completed = true;
                query.statement = null;
                releaseCapacity = true;
            }
        }
        if (releaseCapacity) {
            activeQueries.remove(query.executionId, query);
            capacity.release();
        }
    }

    private static void closeQuietly(PreparedStatement statement) {
        try {
            statement.close();
        } catch (SQLException ignored) {
            // Do not expose JDBC cleanup details.
        }
    }

    public enum CancelResult { REQUESTED, ALREADY_REQUESTED, NOT_FOUND, FAILED }

    public static final class Handle implements AutoCloseable {
        private final ActiveQueryRegistry registry;
        private final ActiveQuery query;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Handle(ActiveQueryRegistry registry, ActiveQuery query) {
            this.registry = registry;
            this.query = query;
        }

        public void attachStatement(PreparedStatement statement) throws SQLException {
            registry.attachStatement(query, Objects.requireNonNull(statement, "statement"));
        }

        public boolean cancellationRequested() {
            return registry.cancellationRequested(query);
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                registry.finish(query);
            }
        }
    }

    public static final class DuplicateExecutionException extends RuntimeException {
        public DuplicateExecutionException() { super("An execution with this identifier is already active."); }
    }

    public static final class CapacityExceededException extends RuntimeException {
        public CapacityExceededException() { super("The active query limit has been reached."); }
    }

    private static final class ActiveQuery {
        private final UUID executionId;
        private final UUID userId;
        private final UUID sessionId;
        private final UUID workspaceId;
        private PreparedStatement statement;
        private boolean cancelRequested;
        private boolean completed;

        private ActiveQuery(UUID executionId, UUID userId, UUID sessionId, UUID workspaceId) {
            this.executionId = executionId;
            this.userId = userId;
            this.sessionId = sessionId;
            this.workspaceId = workspaceId;
        }
    }
}
