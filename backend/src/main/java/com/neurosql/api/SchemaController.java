package com.neurosql.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only metadata browser. Kept disabled until an authenticated workspace
 * boundary is in place; enabling it exposes schema and column names.
 */
@RestController
@RequestMapping("/api/schema")
@ConditionalOnProperty(prefix = "neurosql.schema-browser", name = "enabled", havingValue = "true")
public class SchemaController {
    static final String SCHEMAS_SQL = """
            SELECT schema_name
            FROM information_schema.schemata
            WHERE schema_name <> 'information_schema'
              AND schema_name NOT LIKE 'pg_%'
            ORDER BY schema_name
            """;

    static final String TABLES_SQL = """
            SELECT table_schema, table_name, table_type
            FROM information_schema.tables
            WHERE table_schema <> 'information_schema'
              AND table_schema NOT LIKE 'pg_%'
            ORDER BY table_schema, table_name
            """;

    static final String COLUMNS_SQL = """
            SELECT table_schema, table_name, column_name, data_type, is_nullable, ordinal_position
            FROM information_schema.columns
            WHERE table_schema <> 'information_schema'
              AND table_schema NOT LIKE 'pg_%'
            ORDER BY table_schema, table_name, ordinal_position
            """;

    private final JdbcTemplate jdbcTemplate;

    public SchemaController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public SchemaResponse browseSchema() {
        String database = jdbcTemplate.queryForObject("SELECT current_database()", String.class);
        Map<String, SchemaBuilder> schemas = new LinkedHashMap<>();

        for (Map<String, Object> row : jdbcTemplate.queryForList(SCHEMAS_SQL)) {
            String name = text(row, "schema_name");
            schemas.put(name, new SchemaBuilder(name));
        }

        for (Map<String, Object> row : jdbcTemplate.queryForList(TABLES_SQL)) {
            String schemaName = text(row, "table_schema");
            String tableName = text(row, "table_name");
            SchemaBuilder schema = schemas.computeIfAbsent(schemaName, SchemaBuilder::new);
            schema.tables.put(tableName, new TableBuilder(tableName, text(row, "table_type")));
        }

        for (Map<String, Object> row : jdbcTemplate.queryForList(COLUMNS_SQL)) {
            String schemaName = text(row, "table_schema");
            String tableName = text(row, "table_name");
            SchemaBuilder schema = schemas.computeIfAbsent(schemaName, SchemaBuilder::new);
            TableBuilder table = schema.tables.computeIfAbsent(
                    tableName, ignored -> new TableBuilder(tableName, "UNKNOWN"));
            table.columns.add(new ColumnInfo(
                    text(row, "column_name"),
                    text(row, "data_type"),
                    "YES".equalsIgnoreCase(text(row, "is_nullable")),
                    ((Number) row.get("ordinal_position")).intValue()));
        }

        List<SchemaInfo> result = schemas.values().stream().map(SchemaBuilder::toInfo).toList();
        int tableCount = schemas.values().stream().mapToInt(schema -> schema.tables.size()).sum();
        return new SchemaResponse(database, result, tableCount, Instant.now());
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? "" : value.toString();
    }

    public record SchemaResponse(String database, List<SchemaInfo> schemas, int tableCount, Instant timestamp) {}
    public record SchemaInfo(String name, List<TableInfo> tables) {}
    public record TableInfo(String name, String type, List<ColumnInfo> columns) {}
    public record ColumnInfo(String name, String dataType, boolean nullable, int ordinalPosition) {}

    private static final class SchemaBuilder {
        private final String name;
        private final Map<String, TableBuilder> tables = new LinkedHashMap<>();

        private SchemaBuilder(String name) {
            this.name = name;
        }

        private SchemaInfo toInfo() {
            return new SchemaInfo(name, tables.values().stream().map(TableBuilder::toInfo).toList());
        }
    }

    private static final class TableBuilder {
        private final String name;
        private final String type;
        private final List<ColumnInfo> columns = new ArrayList<>();

        private TableBuilder(String name, String type) {
            this.name = name;
            this.type = type;
        }

        private TableInfo toInfo() {
            return new TableInfo(name, type, List.copyOf(columns));
        }
    }
}
