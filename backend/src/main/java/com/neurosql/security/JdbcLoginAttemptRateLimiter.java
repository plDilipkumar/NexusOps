package com.neurosql.security;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shared PostgreSQL rate limiter for durable login. It is available only when
 * both durable sessions and shared throttling are explicitly enabled.
 *
 * <p>Existing identities are serialized with SELECT FOR UPDATE. New identities
 * use a transaction-scoped advisory lock only while enforcing the global row
 * cap, so ordinary attempts for different existing users do not share a lock.
 * The raw identity is never stored; only its HMAC-SHA256 digest is persisted.</p>
 */
@Repository
@Primary
@ConditionalOnProperty(
        prefix = "neurosql.auth",
        name = {"durable-sessions-enabled", "shared-rate-limit-enabled"},
        havingValue = "true")
public class JdbcLoginAttemptRateLimiter implements DurableLoginAttemptRateLimiter {
    static final int MAX_TRACKED_IDENTITIES = 10_000;
    private static final int CLEANUP_BATCH_SIZE = 100;
    private static final int CLEANUP_EVERY_OPERATIONS = 64;
    private static final int ADVISORY_LOCK_NAMESPACE = 1_314_079_052; // "NSQL"
    private static final int ADVISORY_LOCK_KEY = 1;

    private static final String LOCK_IDENTITY_SQL = """
            SELECT identity_hash
            FROM neurosql_app.auth_login_rate_limits
            WHERE identity_hash = ?
            FOR UPDATE
            """;

    private static final String INSERT_FIRST_FAILURE_SQL = """
            INSERT INTO neurosql_app.auth_login_rate_limits
                (identity_hash, failure_timestamps, blocked_until, updated_at)
            VALUES (?, ARRAY[now()], NULL, now())
            """;

    private static final String UPDATE_FAILURE_SQL = """
            UPDATE neurosql_app.auth_login_rate_limits AS current
            SET failure_timestamps = CASE
                    WHEN current.blocked_until > now() THEN current.failure_timestamps
                    ELSE (
                        SELECT COALESCE(
                            array_agg(recent.failure_at ORDER BY recent.failure_at),
                            ARRAY[]::timestamptz[])
                        FROM (
                            SELECT failures.failure_at
                            FROM unnest(current.failure_timestamps) AS failures(failure_at)
                            WHERE failures.failure_at > now() - INTERVAL '15 minutes'
                            UNION ALL
                            SELECT now()
                        ) AS recent
                    )
                END,
                blocked_until = CASE
                    WHEN current.blocked_until > now() THEN current.blocked_until
                    WHEN (
                        SELECT count(*)
                        FROM (
                            SELECT failures.failure_at
                            FROM unnest(current.failure_timestamps) AS failures(failure_at)
                            WHERE failures.failure_at > now() - INTERVAL '15 minutes'
                            UNION ALL
                            SELECT now()
                        ) AS recent
                    ) >= 5 THEN now() + INTERVAL '15 minutes'
                    ELSE NULL
                END,
                updated_at = now()
            WHERE identity_hash = ?
            """;

    private static final String DELETE_OLDEST_UNBLOCKED_SQL = """
            DELETE FROM neurosql_app.auth_login_rate_limits
            WHERE identity_hash = (
                SELECT identity_hash
                FROM neurosql_app.auth_login_rate_limits
                WHERE blocked_until IS NULL OR blocked_until <= now()
                ORDER BY updated_at ASC, identity_hash ASC
                LIMIT 1
            )
            """;

    private static final String CLEANUP_STALE_SQL = """
            DELETE FROM neurosql_app.auth_login_rate_limits
            WHERE identity_hash IN (
                SELECT identity_hash
                FROM neurosql_app.auth_login_rate_limits
                WHERE updated_at < now() - INTERVAL '24 hours'
                  AND (blocked_until IS NULL OR blocked_until <= now())
                ORDER BY updated_at ASC, identity_hash ASC
                LIMIT ?
            )
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final LoginIdentityHasher identityHasher;
    private final AtomicInteger operations = new AtomicInteger();

    public JdbcLoginAttemptRateLimiter(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            @Value("${neurosql.auth.rate-limit-hmac-key:}") String hmacKey) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.identityHasher = new LoginIdentityHasher(hmacKey);
    }

    @Override
    public long retryAfterSeconds(String identity) {
        String hash = identityHasher.hash(identity);
        List<Long> seconds = jdbc.query("""
                SELECT CEIL(EXTRACT(EPOCH FROM (blocked_until - now())))::bigint
                FROM neurosql_app.auth_login_rate_limits
                WHERE identity_hash = ?
                  AND blocked_until > now()
                """,
                (resultSet, rowNumber) -> resultSet.getLong(1),
                hash);
        return seconds.isEmpty() ? 0 : Math.max(1, seconds.get(0));
    }

    @Override
    public void recordFailure(String identity) {
        String hash = identityHasher.hash(identity);
        transactions.executeWithoutResult(transaction -> {
            boolean exists = lockIdentity(hash);
            if (!exists) {
                // Serialize only new-key admission so the global state cap is race-safe.
                jdbc.query(
                        "SELECT pg_advisory_xact_lock(" + ADVISORY_LOCK_NAMESPACE + ", " + ADVISORY_LOCK_KEY + ")",
                        (RowCallbackHandler) resultSet -> { });
                exists = lockIdentity(hash);
                if (!exists) {
                    Integer tracked = jdbc.queryForObject(
                            "SELECT count(*) FROM neurosql_app.auth_login_rate_limits", Integer.class);
                    if (tracked != null && tracked >= MAX_TRACKED_IDENTITIES) {
                        int removed = jdbc.update(DELETE_OLDEST_UNBLOCKED_SQL);
                        if (removed == 0) {
                            throw new ResponseStatusException(
                                    HttpStatus.SERVICE_UNAVAILABLE,
                                    "Login is temporarily unavailable. Please retry later.");
                        }
                    }
                    jdbc.update(INSERT_FIRST_FAILURE_SQL, hash);
                }
            }
            if (exists) {
                jdbc.update(UPDATE_FAILURE_SQL, hash);
            }
            maybeCleanupWithinTransaction();
        });
    }

    @Override
    public void recordSuccess(String identity) {
        String hash = identityHasher.hash(identity);
        transactions.executeWithoutResult(transaction -> {
            jdbc.update(
                    "DELETE FROM neurosql_app.auth_login_rate_limits WHERE identity_hash = ?",
                    hash);
            maybeCleanupWithinTransaction();
        });
    }

    /** Runs one bounded cleanup batch; exposed package-locally for integration tests. */
    int cleanupStaleRows() {
        Integer deleted = transactions.execute(transaction ->
                jdbc.update(CLEANUP_STALE_SQL, CLEANUP_BATCH_SIZE));
        return deleted == null ? 0 : deleted;
    }

    private boolean lockIdentity(String hash) {
        return !jdbc.query(LOCK_IDENTITY_SQL, (resultSet, rowNumber) -> resultSet.getString(1), hash).isEmpty();
    }

    private void maybeCleanupWithinTransaction() {
        if (operations.incrementAndGet() % CLEANUP_EVERY_OPERATIONS == 0) {
            jdbc.update(CLEANUP_STALE_SQL, CLEANUP_BATCH_SIZE);
        }
    }
}
