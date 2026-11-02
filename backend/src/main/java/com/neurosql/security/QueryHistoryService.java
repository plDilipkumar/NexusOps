package com.neurosql.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Records bounded metadata for actual SQL execution attempts made by a durable
 * session. Validation rejections and infrastructure-readiness failures are not
 * recorded as user query executions.
 */
@Service
@ConditionalOnProperty(
        prefix = "neurosql.auth",
        name = {"durable-sessions-enabled", "shared-rate-limit-enabled"},
        havingValue = "true")
@ConditionalOnProperty(
        prefix = "neurosql.sql-history",
        name = "enabled",
        havingValue = "true")
public class QueryHistoryService {
    private static final Logger LOGGER = LoggerFactory.getLogger(QueryHistoryService.class);
    private static final int MAX_QUERY_CHARACTERS = 50_000;
    private static final int MAX_EXECUTION_MILLIS = 30_000;

    private final JdbcQueryHistoryRepository repository;

    public QueryHistoryService(JdbcQueryHistoryRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public void recordSucceeded(
            UUID userId,
            UUID workspaceId,
            String sql,
            int rowCount,
            long executionTimeMillis,
            boolean truncated) {
        if (rowCount < 0) {
            throw new IllegalArgumentException("rowCount must not be negative.");
        }
        append(userId, workspaceId, sql, "SUCCEEDED", rowCount, executionTimeMillis, truncated);
    }

    public void recordFailed(
            UUID userId,
            UUID workspaceId,
            String sql,
            long executionTimeMillis) {
        append(userId, workspaceId, sql, "FAILED", null, executionTimeMillis, false);
    }

    private void append(
            UUID userId,
            UUID workspaceId,
            String sql,
            String outcome,
            Integer rowCount,
            long executionTimeMillis,
            boolean truncated) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        String queryText = sql == null ? "" : sql.trim();
        if (queryText.isEmpty() || queryText.length() > MAX_QUERY_CHARACTERS) {
            throw new IllegalArgumentException("SQL history text must contain between 1 and 50000 characters.");
        }

        int safeExecutionTime = (int) Math.min(
                MAX_EXECUTION_MILLIS,
                Math.max(0L, executionTimeMillis));
        try {
            repository.append(
                    workspaceId,
                    userId,
                    queryText,
                    sha256(queryText),
                    outcome,
                    rowCount,
                    safeExecutionTime,
                    truncated);
        } catch (DataAccessException unavailable) {
            // A history-write failure must not turn a successfully executed query into an API failure.
            // Do not log SQL, workspace IDs, account IDs, or database/provider details.
            LOGGER.warn("Query history could not be persisted; the SQL execution result remains available.");
        }
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }
}
