package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.neurosql.api.ActiveQueryRegistry;
import com.neurosql.api.SqlExecutionController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Exercises session-bound SQL cancellation through real HTTP, the durable-auth
 * PostgreSQL repositories, and a separate restricted PostgreSQL login. The
 * environment guard deliberately permits only the disposable loopback
 * neurosql_test database; this test must never target Neon or production.
 */
@EnabledIfEnvironmentVariable(
        named = "NEUROSQL_TEST_JDBC_URL",
        matches = "^jdbc:postgresql://(localhost|127\\.0\\.0\\.1)(:[0-9]+)?/neurosql_test(\\?.*)?$")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "neurosql.auth.enabled=false",
                "neurosql.auth.durable-sessions-enabled=true",
                "neurosql.auth.shared-rate-limit-enabled=true",
                "neurosql.auth.rate-limit-hmac-key=ci-only-shared-rate-limit-hmac-key-32-bytes",
                "neurosql.auth.expected-database=neurosql_test",
                "neurosql.schema-browser.enabled=true",
                "neurosql.sql-execution.enabled=true",
                "neurosql.sql-execution.max-rows=25",
                "neurosql.sql-execution.timeout-seconds=15"
        })
class DurableSessionSqlRevocationPostgresIntegrationTest {
    private static final String PASSWORD = "Correct-Horse-Battery-2026";
    private static final String TEST_RATE_LIMIT_HMAC_KEY =
            "ci-only-shared-rate-limit-hmac-key-32-bytes";
    private static final String ROLE_SUFFIX = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final String SQL_READONLY_ROLE = "neurosql_sql_readonly_" + ROLE_SUFFIX;
    private static final String SQL_LOGIN_ROLE = "neurosql_sql_login_" + ROLE_SUFFIX;
    private static final String SQL_LOGIN_PASSWORD = UUID.randomUUID().toString().replace("-", "");
    private static volatile boolean sqlRolePrepared;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ActiveQueryRegistry activeQueries;

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void configureDisposableDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> requiredEnvironment("NEUROSQL_TEST_JDBC_URL"));
        registry.add("spring.datasource.username", () -> requiredEnvironment("NEUROSQL_TEST_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> requiredEnvironment("NEUROSQL_TEST_DB_PASSWORD"));
        registry.add("neurosql.sql-execution.expected-role", () -> SQL_READONLY_ROLE);
        registry.add("neurosql.sql-execution.jdbc-url", () -> {
            ensureDisposableSqlRole();
            return requiredEnvironment("NEUROSQL_TEST_JDBC_URL");
        });
        registry.add("neurosql.sql-execution.username", () -> SQL_LOGIN_ROLE);
        registry.add("neurosql.sql-execution.password", () -> SQL_LOGIN_PASSWORD);
    }

    private static synchronized void ensureDisposableSqlRole() {
        if (sqlRolePrepared) {
            return;
        }
        String url = requiredEnvironment("NEUROSQL_TEST_JDBC_URL");
        if (!url.matches("^jdbc:postgresql://(localhost|127\\.0\\.0\\.1)(:[0-9]+)?/neurosql_test(\\?.*)?$")) {
            throw new IllegalStateException("The SQL role fixture only permits the disposable neurosql_test database.");
        }
        try (Connection connection = DriverManager.getConnection(
                        url,
                        requiredEnvironment("NEUROSQL_TEST_DB_USERNAME"),
                        requiredEnvironment("NEUROSQL_TEST_DB_PASSWORD"));
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS neurosql_app");
            statement.execute("CREATE ROLE " + SQL_READONLY_ROLE
                    + " NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
            statement.execute("CREATE ROLE " + SQL_LOGIN_ROLE
                    + " LOGIN PASSWORD '" + SQL_LOGIN_PASSWORD
                    + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
            statement.execute("GRANT " + SQL_READONLY_ROLE + " TO " + SQL_LOGIN_ROLE);
            statement.execute("GRANT USAGE ON SCHEMA neurosql_app TO " + SQL_READONLY_ROLE);
            sqlRolePrepared = true;
        } catch (Exception failure) {
            throw new IllegalStateException("Could not prepare the disposable read-only SQL role.", failure);
        }
    }

    @BeforeEach
    void createNonDestructiveFixture() {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS neurosql_app");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS neurosql_app.app_users (
                    user_id UUID PRIMARY KEY,
                    status VARCHAR(24) NOT NULL
                )
                """);
        jdbc.execute("ALTER TABLE neurosql_app.app_users ADD COLUMN IF NOT EXISTS email VARCHAR(320)");
        jdbc.execute("ALTER TABLE neurosql_app.app_users ADD COLUMN IF NOT EXISTS password_hash VARCHAR(255)");
        jdbc.execute("ALTER TABLE neurosql_app.app_users ADD COLUMN IF NOT EXISTS display_name VARCHAR(120)");
        jdbc.execute("ALTER TABLE neurosql_app.app_users ADD COLUMN IF NOT EXISTS email_verified_at TIMESTAMPTZ");

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS neurosql_app.workspaces (
                    workspace_id UUID PRIMARY KEY,
                    name VARCHAR(120) NOT NULL,
                    slug VARCHAR(120) NOT NULL,
                    created_by_user_id UUID
                )
                """);
        jdbc.execute("ALTER TABLE neurosql_app.workspaces ADD COLUMN IF NOT EXISTS created_by_user_id UUID");

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS neurosql_app.workspace_memberships (
                    workspace_id UUID NOT NULL REFERENCES neurosql_app.workspaces(workspace_id),
                    user_id UUID NOT NULL REFERENCES neurosql_app.app_users(user_id),
                    role VARCHAR(16) NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    PRIMARY KEY (workspace_id, user_id)
                )
                """);

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS neurosql_app.user_sessions (
                    session_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    user_id UUID NOT NULL REFERENCES neurosql_app.app_users(user_id) ON DELETE CASCADE,
                    token_hash VARCHAR(64) NOT NULL UNIQUE,
                    created_at TIMESTAMPTZ NOT NULL,
                    expires_at TIMESTAMPTZ NOT NULL,
                    revoked_at TIMESTAMPTZ,
                    CHECK (expires_at > created_at),
                    CHECK (expires_at <= created_at + INTERVAL '12 hours'),
                    CHECK (revoked_at IS NULL OR revoked_at >= created_at)
                )
                """);

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS neurosql_app.auth_login_rate_limits (
                    identity_hash VARCHAR(64) PRIMARY KEY
                        CHECK (identity_hash ~ '^[0-9a-f]{64}$'),
                    failure_timestamps TIMESTAMPTZ[] NOT NULL DEFAULT ARRAY[]::TIMESTAMPTZ[],
                    blocked_until TIMESTAMPTZ,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    CONSTRAINT auth_login_rate_limits_failure_timestamps_check
                        CHECK (cardinality(failure_timestamps) <= 30),
                    CONSTRAINT auth_login_rate_limits_failure_timestamps_no_null_check
                        CHECK (array_position(failure_timestamps, NULL) IS NULL),
                    CONSTRAINT auth_login_rate_limit_block_consistency CHECK (
                        (blocked_until IS NULL AND cardinality(failure_timestamps) < 30)
                        OR (blocked_until IS NOT NULL
                            AND cardinality(failure_timestamps) BETWEEN 5 AND 30)
                    )
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS ix_auth_login_rate_limits_updated_at
                ON neurosql_app.auth_login_rate_limits (updated_at)
                """);
    }

    @Test
    void logoutRevokesSessionCancelsActiveSqlAndPreventsLaterExecution() throws Exception {
        Fixture fixture = insertFixture();
        String token = login(fixture.email());
        UUID executionId = UUID.randomUUID();
        String marker = "revoked_sql_" + UUID.randomUUID().toString().replace("-", "");
        ExecutorService queryThread = Executors.newSingleThreadExecutor();
        Future<ResponseEntity<JsonNode>> runningQuery =
                startQuery(queryThread, token, fixture.workspaceId(), executionId, marker);

        try {
            awaitActiveQuery(marker);
            assertThat(activeQueries.activeCount()).isEqualTo(1);

            ResponseEntity<Void> logout = http.exchange(
                    baseUrl() + "/api/auth/logout",
                    HttpMethod.POST,
                    new HttpEntity<>(bearer(token)),
                    Void.class);
            assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(logout.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).contains("no-store");

            ResponseEntity<JsonNode> rejectedIdentity = http.exchange(
                    baseUrl() + "/api/auth/me",
                    HttpMethod.GET,
                    new HttpEntity<>(bearer(token)),
                    JsonNode.class);
            assertThat(rejectedIdentity.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

            ResponseEntity<JsonNode> rejectedNewQuery = execute(token, fixture.workspaceId(),
                    UUID.randomUUID(), "SELECT 1");
            assertThat(rejectedNewQuery.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(rejectedNewQuery.getBody().toString()).doesNotContain(marker);

            ResponseEntity<JsonNode> rejectedCancellation = cancel(
                    token, fixture.workspaceId(), executionId);
            assertThat(rejectedCancellation.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

            ResponseEntity<JsonNode> completed = runningQuery.get(10, TimeUnit.SECONDS);
            assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(completed.getBody().path("message").asText()).isEqualTo("The query was cancelled.");
            assertThat(completed.getBody().toString()).doesNotContain(marker);
            assertThat(activeQueries.activeCount()).isZero();
            assertThat(activeQueryCount(marker)).isZero();
        } finally {
            runningQuery.cancel(true);
            queryThread.shutdownNow();
            queryThread.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void cancellationRequiresTheSameSessionWorkspaceAndExecutionIdentifier() throws Exception {
        Fixture fixture = insertFixture();
        String token = login(fixture.email());
        String secondSessionToken = login(fixture.email());
        String otherUserToken = login(fixture.otherEmail());
        UUID executionId = UUID.randomUUID();
        String marker = "scoped_sql_" + UUID.randomUUID().toString().replace("-", "");
        ExecutorService queryThread = Executors.newSingleThreadExecutor();
        Future<ResponseEntity<JsonNode>> runningQuery =
                startQuery(queryThread, token, fixture.workspaceId(), executionId, marker);

        try {
            awaitActiveQuery(marker);

            ResponseEntity<JsonNode> otherUser = cancel(
                    otherUserToken, fixture.otherWorkspaceId(), executionId);
            assertThat(otherUser.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

            ResponseEntity<JsonNode> otherSession = cancel(
                    secondSessionToken, fixture.workspaceId(), executionId);
            assertThat(otherSession.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

            ResponseEntity<JsonNode> wrongExecution = cancel(
                    token, fixture.workspaceId(), UUID.randomUUID());
            assertThat(wrongExecution.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

            ResponseEntity<JsonNode> wrongWorkspace = cancel(
                    token, fixture.otherWorkspaceId(), executionId);
            assertThat(wrongWorkspace.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

            assertThat(activeQueryCount(marker)).isEqualTo(1);
            assertThat(activeQueries.activeCount()).isEqualTo(1);

            ResponseEntity<JsonNode> accepted = cancel(token, fixture.workspaceId(), executionId);
            assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(accepted.getBody().path("status").asText()).isEqualTo("CANCEL_REQUESTED");

            ResponseEntity<JsonNode> completed = runningQuery.get(10, TimeUnit.SECONDS);
            assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(completed.getBody().path("message").asText()).isEqualTo("The query was cancelled.");
            assertThat(completed.getBody().toString()).doesNotContain(marker);
            assertThat(activeQueries.activeCount()).isZero();
        } finally {
            runningQuery.cancel(true);
            queryThread.shutdownNow();
            queryThread.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    private Future<ResponseEntity<JsonNode>> startQuery(
            ExecutorService executor,
            String token,
            UUID workspaceId,
            UUID executionId,
            String marker) {
        String sql = "SELECT pg_sleep(10), '" + marker + "' AS result /* " + marker + " */";
        HttpHeaders headers = sqlHeaders(token, workspaceId);
        headers.set(ActiveQueryRegistry.EXECUTION_ID_HEADER, executionId.toString());
        return executor.submit(() -> http.exchange(
                baseUrl() + "/api/sql/execute",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("sql", sql), headers),
                JsonNode.class));
    }

    private ResponseEntity<JsonNode> execute(
            String token,
            UUID workspaceId,
            UUID executionId,
            String sql) {
        HttpHeaders headers = sqlHeaders(token, workspaceId);
        headers.set(ActiveQueryRegistry.EXECUTION_ID_HEADER, executionId.toString());
        return http.exchange(
                baseUrl() + "/api/sql/execute",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("sql", sql), headers),
                JsonNode.class);
    }

    private ResponseEntity<JsonNode> cancel(String token, UUID workspaceId, UUID executionId) {
        HttpHeaders headers = sqlHeaders(token, workspaceId);
        return http.exchange(
                baseUrl() + "/api/sql/cancel",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("executionId", executionId), headers),
                JsonNode.class);
    }

    private String login(String email) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> response = http.exchange(
                baseUrl() + "/api/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("email", email, "password", PASSWORD), headers),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        String token = response.getBody().path("token").asText();
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        return token;
    }

    private Fixture insertFixture() {
        UUID userId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID otherWorkspaceId = UUID.randomUUID();
        String email = "session-sql-" + UUID.randomUUID() + "@example.com";
        String otherEmail = "session-sql-other-" + UUID.randomUUID() + "@example.com";
        String passwordHash = passwordEncoder.encode(PASSWORD);

        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            insertUser(userId, email, passwordHash, "SQL Session User");
            insertUser(otherUserId, otherEmail, passwordHash, "Other SQL Session User");
            insertWorkspace(workspaceId, userId);
            insertWorkspace(otherWorkspaceId, otherUserId);
            insertMembership(workspaceId, userId);
            insertMembership(otherWorkspaceId, otherUserId);
        });
        return new Fixture(userId, otherUserId, workspaceId, otherWorkspaceId, email, otherEmail);
    }

    private void insertUser(UUID userId, String email, String passwordHash, String displayName) {
        jdbc.update("""
                INSERT INTO neurosql_app.app_users
                    (user_id, email, password_hash, display_name, status, email_verified_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', now())
                """,
                userId, email, passwordHash, displayName);
    }

    private void insertWorkspace(UUID workspaceId, UUID createdBy) {
        String slug = "sql-team-" + workspaceId.toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO neurosql_app.workspaces (workspace_id, name, slug, created_by_user_id)
                VALUES (?, ?, ?, ?)
                """,
                workspaceId, "SQL integration " + slug, slug, createdBy);
    }

    private void insertMembership(UUID workspaceId, UUID userId) {
        jdbc.update("""
                INSERT INTO neurosql_app.workspace_memberships (workspace_id, user_id, role, status)
                VALUES (?, ?, 'OWNER', 'ACTIVE')
                """,
                workspaceId, userId);
    }

    private void awaitActiveQuery(String marker) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (activeQueryCount(marker) > 0) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("The PostgreSQL read-only query did not become active before the test deadline.");
    }

    private int activeQueryCount(String marker) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity "
                        + "WHERE datname = current_database() AND state = 'active' AND strpos(query, ?) > 0",
                Integer.class,
                marker);
    }

    private static HttpHeaders sqlHeaders(String token, UUID workspaceId) {
        HttpHeaders headers = bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(WorkspaceAuthorizationFilter.WORKSPACE_HEADER, workspaceId.toString());
        return headers;
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set for the disposable PostgreSQL integration test.");
        }
        return value;
    }

    private record Fixture(
            UUID userId,
            UUID otherUserId,
            UUID workspaceId,
            UUID otherWorkspaceId,
            String email,
            String otherEmail) {
    }
}
