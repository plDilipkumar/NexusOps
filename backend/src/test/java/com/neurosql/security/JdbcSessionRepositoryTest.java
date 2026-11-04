package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcSessionRepositoryTest {
    private static final Instant ISSUED_AT = Instant.parse("2026-10-28T08:00:00Z");
    private static final Instant EXPIRES_AT = ISSUED_AT.plusSeconds(12 * 60 * 60);
    private static final String TOKEN_HASH = "a".repeat(64);
    private static final UUID USER_ID = UUID.fromString("b4f41d45-68dc-4f7b-8df0-48e3f29e54c1");

    @Test
    void persistsOnlyCanonicalHashAndBoundedTimestamps() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        JdbcSessionRepository repository = new JdbcSessionRepository(jdbc);

        repository.createSession(USER_ID, TOKEN_HASH, ISSUED_AT, EXPIRES_AT);

        assertThat(jdbc.lastSql).contains("INSERT INTO neurosql_app.user_sessions");
        assertThat(jdbc.lastArgs).containsExactly(
                USER_ID, TOKEN_HASH, Timestamp.from(ISSUED_AT), Timestamp.from(EXPIRES_AT));
        assertThat(jdbc.lastArgs).doesNotContain("raw-bearer-token");
    }

    @Test
    void rejectsMalformedHashAndExpiryBeyondMaximumLifetimeBeforeDatabaseAccess() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        JdbcSessionRepository repository = new JdbcSessionRepository(jdbc);

        assertThatThrownBy(() -> repository.createSession(USER_ID, "raw-bearer-token", ISSUED_AT, EXPIRES_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.createSession(
                USER_ID, TOKEN_HASH, ISSUED_AT, EXPIRES_AT.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.createSession(
                USER_ID, TOKEN_HASH, ISSUED_AT, ISSUED_AT))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(jdbc.lastSql).isNull();
    }

    @Test
    void activeLookupRequiresUnrevokedUnexpiredSessionAndActiveUser() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        JdbcSessionRepository.StoredSession expected = new JdbcSessionRepository.StoredSession(
                UUID.fromString("4cfa688e-8890-4b5e-8a9a-19f074d5a1f3"),
                USER_ID,
                ISSUED_AT,
                EXPIRES_AT);
        jdbc.queryResult = List.of(expected);
        JdbcSessionRepository repository = new JdbcSessionRepository(jdbc);
        Instant now = ISSUED_AT.plusSeconds(60);

        assertThat(repository.findActiveByTokenHash(TOKEN_HASH, now)).contains(expected);
        assertThat(jdbc.lastSql)
                .contains("s.revoked_at IS NULL")
                .contains("s.created_at <= ?")
                .contains("s.expires_at > ?")
                .contains("INTERVAL '12 hours'")
                .contains("u.status = 'ACTIVE'");
        assertThat(jdbc.lastArgs).containsExactly(
                TOKEN_HASH, Timestamp.from(now), Timestamp.from(now));
    }

    @Test
    void malformedLookupHashFailsClosedWithoutQueryingDatabase() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        JdbcSessionRepository repository = new JdbcSessionRepository(jdbc);

        assertThat(repository.findActiveByTokenHash("not-a-hash", ISSUED_AT)).isEmpty();
        assertThat(repository.findActiveByTokenHash(TOKEN_HASH, null)).isEmpty();
        assertThat(jdbc.lastSql).isNull();
    }

    @Test
    void revocationIsIdempotentAndRejectsMalformedHash() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        JdbcSessionRepository repository = new JdbcSessionRepository(jdbc);

        assertThat(repository.revokeByTokenHash(TOKEN_HASH, ISSUED_AT.plusSeconds(30))).isTrue();
        assertThat(jdbc.lastSql)
                .contains("SET revoked_at = GREATEST(?, created_at)")
                .contains("AND revoked_at IS NULL");
        assertThat(jdbc.lastArgs).containsExactly(Timestamp.from(ISSUED_AT.plusSeconds(30)), TOKEN_HASH);

        jdbc.updateResult = 0;
        assertThat(repository.revokeByTokenHash(TOKEN_HASH, ISSUED_AT.plusSeconds(60))).isFalse();
        assertThat(repository.revokeByTokenHash("bad", ISSUED_AT)).isFalse();
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private String lastSql;
        private Object[] lastArgs;
        private int updateResult = 1;
        private List<?> queryResult = List.of();

        @Override
        public int update(String sql, Object... args) {
            lastSql = sql;
            lastArgs = args;
            return updateResult;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            lastSql = sql;
            lastArgs = args;
            return (List<T>) queryResult;
        }
    }
}
