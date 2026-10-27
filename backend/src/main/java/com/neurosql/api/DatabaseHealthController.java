package com.neurosql.api;

import java.time.Instant;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DatabaseHealthController {
    private final JdbcTemplate jdbcTemplate;

    public DatabaseHealthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/api/health/database")
    public ResponseEntity<Map<String, Object>> databaseHealth() {
        try {
            Map<String, Object> database = jdbcTemplate.queryForMap(
                "SELECT current_database() AS database_name, current_setting('server_version') AS postgres_version"
            );
            return ResponseEntity.ok(Map.of(
                "status", "UP",
                "database", database.get("database_name"),
                "postgresVersion", database.get("postgres_version"),
                "timestamp", Instant.now().toString()
            ));
        } catch (DataAccessException ex) {
            // Keep connection details and driver errors out of public health responses.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "status", "DOWN",
                "timestamp", Instant.now().toString()
            ));
        }
    }
}