package com.neurosql.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

class DatabaseHealthControllerTest {
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final DatabaseHealthController controller = new DatabaseHealthController(jdbcTemplate);

    @Test
    void reportsDatabaseNameAndVersionWhenConnectionWorks() {
        when(jdbcTemplate.queryForMap(anyString())).thenReturn(Map.of(
            "database_name", "neurosql_db",
            "postgres_version", "17.11"
        ));

        ResponseEntity<Map<String, Object>> response = controller.databaseHealth();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
            .containsEntry("status", "UP")
            .containsEntry("database", "neurosql_db")
            .containsEntry("postgresVersion", "17.11")
            .containsKey("timestamp");
        verify(jdbcTemplate).queryForMap(anyString());
    }

    @Test
    void returnsServiceUnavailableWithoutLeakingConnectionDetailsWhenDatabaseIsDown() {
        when(jdbcTemplate.queryForMap(anyString()))
            .thenThrow(new DataAccessResourceFailureException("private connection detail"));

        ResponseEntity<Map<String, Object>> response = controller.databaseHealth();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody())
            .containsEntry("status", "DOWN")
            .containsKey("timestamp")
            .doesNotContainKey("database")
            .doesNotContainKey("error");
    }
}