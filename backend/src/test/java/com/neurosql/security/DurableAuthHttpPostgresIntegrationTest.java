package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
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
 * HTTP-to-JDBC authentication lifecycle test using only the disposable CI
 * PostgreSQL service. The URL guard deliberately refuses non-loopback hosts
 * and databases other than neurosql_test; never point this at Neon or production.
 */
@EnabledIfEnvironmentVariable(
        named = "NEUROSQL_TEST_JDBC_URL",
        matches = "^jdbc:postgresql://(localhost|127\\.0\\.0\\.1)(:[0-9]+)?/neurosql_test(\\?.*)?$")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "neurosql.auth.enabled=false",
                "neurosql.auth.durable-sessions-enabled=true",
                "neurosql.schema-browser.enabled=true"
        })
class DurableAuthHttpPostgresIntegrationTest {
    private static final String PASSWORD = "Correct-Horse-Battery-2026";

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void configureDisposableDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> requiredEnvironment("NEUROSQL_TEST_JDBC_URL"));
        registry.add("spring.datasource.username", () -> requiredEnvironment("NEUROSQL_TEST_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> requiredEnvironment("NEUROSQL_TEST_DB_PASSWORD"));
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
                    CHECK (revoked_at IS NULL OR revoked_at >= created_at)
                )
                """);
    }

    @Test
    void loginWorkspaceAuthorizationSchemaAndLogoutUseRealHttpAndPostgres() {
        Fixture fixture = insertFixture();
        String baseUrl = "http://localhost:" + port;

        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> login = http.exchange(
                baseUrl + "/api/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("email", fixture.email(), "password", PASSWORD), loginHeaders),
                JsonNode.class);

        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        String token = login.getBody().path("token").asText();
        assertThat(token).matches("[A-Za-z0-9_-]{43}");

        HttpHeaders bearerHeaders = bearer(token);
        ResponseEntity<JsonNode> identity = http.exchange(
                baseUrl + "/api/auth/me", HttpMethod.GET, new HttpEntity<>(bearerHeaders), JsonNode.class);
        assertThat(identity.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(identity.getBody().path("userId").asText()).isEqualTo(fixture.userId().toString());

        ResponseEntity<JsonNode> workspaces = http.exchange(
                baseUrl + "/api/auth/workspaces",
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders),
                JsonNode.class);
        assertThat(workspaces.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(workspaces.getBody().size()).isEqualTo(1);
        assertThat(workspaces.getBody().get(0).path("workspaceId").asText())
                .isEqualTo(fixture.workspaceId().toString());
        assertThat(workspaces.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).contains("no-store");

        HttpHeaders otherWorkspaceHeaders = bearer(token);
        otherWorkspaceHeaders.set(WorkspaceAuthorizationFilter.WORKSPACE_HEADER, fixture.unrelatedWorkspaceId().toString());
        ResponseEntity<JsonNode> deniedSchema = http.exchange(
                baseUrl + "/api/schema",
                HttpMethod.GET,
                new HttpEntity<>(otherWorkspaceHeaders),
                JsonNode.class);
        assertThat(deniedSchema.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(deniedSchema.getBody().path("error").asText()).isEqualTo("workspace_access_denied");

        HttpHeaders authorizedWorkspaceHeaders = bearer(token);
        authorizedWorkspaceHeaders.set(WorkspaceAuthorizationFilter.WORKSPACE_HEADER, fixture.workspaceId().toString());
        ResponseEntity<JsonNode> schema = http.exchange(
                baseUrl + "/api/schema",
                HttpMethod.GET,
                new HttpEntity<>(authorizedWorkspaceHeaders),
                JsonNode.class);
        assertThat(schema.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(schema.getBody().path("database").asText()).isEqualTo("neurosql_test");
        assertThat(schema.getBody().path("schemas").toString()).contains("neurosql_app", "app_users");

        ResponseEntity<Void> logout = http.exchange(
                baseUrl + "/api/auth/logout",
                HttpMethod.POST,
                new HttpEntity<>(bearerHeaders),
                Void.class);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(logout.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).contains("no-store");

        ResponseEntity<JsonNode> afterLogout = http.exchange(
                baseUrl + "/api/auth/me", HttpMethod.GET, new HttpEntity<>(bearerHeaders), JsonNode.class);
        assertThat(afterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void repeatedFailedLoginAttemptsAreThrottledWithoutCreatingSessions() {
        Fixture fixture = insertFixture();
        String baseUrl = "http://localhost:" + port;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        for (int attempt = 0; attempt < BasicAuthAttemptRateLimiter.MAX_FAILED_ATTEMPTS; attempt++) {
            ResponseEntity<JsonNode> failed = http.exchange(
                    baseUrl + "/api/auth/login",
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of("email", fixture.email(), "password", "Definitely-Wrong-Password-2026"), headers),
                    JsonNode.class);
            assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(failed.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        }

        ResponseEntity<JsonNode> throttled = http.exchange(
                baseUrl + "/api/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("email", fixture.email(), "password", "Definitely-Wrong-Password-2026"), headers),
                JsonNode.class);
        assertThat(throttled.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        String retryAfter = throttled.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        assertThat(retryAfter).matches("\\d+");
        assertThat(Long.parseLong(retryAfter))
                .isBetween(1L, BasicAuthAttemptRateLimiter.BLOCK_DURATION.toSeconds());

        Integer sessionCount = jdbc.queryForObject(
                "SELECT count(*) FROM neurosql_app.user_sessions WHERE user_id = ?",
                Integer.class,
                fixture.userId());
        assertThat(sessionCount).isZero();
    }

    private Fixture insertFixture() {
        UUID userId = UUID.randomUUID();
        UUID otherOwnerId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID unrelatedWorkspaceId = UUID.randomUUID();
        String email = "http-integration-" + UUID.randomUUID() + "@example.com";
        String otherEmail = "http-owner-" + UUID.randomUUID() + "@example.com";
        String passwordHash = passwordEncoder.encode(PASSWORD);
        String otherPasswordHash = passwordEncoder.encode("Other-Owner-Password-2026");

        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            insertUser(userId, email, passwordHash, "HTTP Integration User");
            insertUser(otherOwnerId, otherEmail, otherPasswordHash, "Other Workspace Owner");
            insertWorkspace(workspaceId, "http-team-" + shortId(workspaceId), userId);
            insertWorkspace(unrelatedWorkspaceId, "other-team-" + shortId(unrelatedWorkspaceId), otherOwnerId);
            insertMembership(workspaceId, userId, "OWNER");
            insertMembership(unrelatedWorkspaceId, otherOwnerId, "OWNER");
        });
        return new Fixture(userId, workspaceId, unrelatedWorkspaceId, email);
    }

    private void insertUser(UUID userId, String email, String passwordHash, String displayName) {
        jdbc.update("""
                INSERT INTO neurosql_app.app_users
                    (user_id, email, password_hash, display_name, status, email_verified_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', now())
                """,
                userId, email, passwordHash, displayName);
    }

    private void insertWorkspace(UUID workspaceId, String slug, UUID createdBy) {
        jdbc.update("""
                INSERT INTO neurosql_app.workspaces (workspace_id, name, slug, created_by_user_id)
                VALUES (?, ?, ?, ?)
                """,
                workspaceId, "Integration workspace " + slug, slug, createdBy);
    }

    private void insertMembership(UUID workspaceId, UUID userId, String role) {
        jdbc.update("""
                INSERT INTO neurosql_app.workspace_memberships (workspace_id, user_id, role, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """,
                workspaceId, userId, role);
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set for the disposable PostgreSQL integration test.");
        }
        return value;
    }

    private record Fixture(UUID userId, UUID workspaceId, UUID unrelatedWorkspaceId, String email) {
    }
}
