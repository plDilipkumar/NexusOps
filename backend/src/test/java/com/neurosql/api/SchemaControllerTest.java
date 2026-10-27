package com.neurosql.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SchemaController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "neurosql.schema-browser.enabled=true")
class SchemaControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JdbcTemplate jdbcTemplate;

    @Test
    void returnsVisibleSchemasWhenDatabaseIsEmpty() throws Exception {
        when(jdbcTemplate.queryForObject(eq("SELECT current_database()"), eq(String.class)))
                .thenReturn("neurosql_db");
        when(jdbcTemplate.queryForList(SchemaController.SCHEMAS_SQL))
                .thenReturn(List.of(Map.of("schema_name", "public")));
        when(jdbcTemplate.queryForList(SchemaController.TABLES_SQL)).thenReturn(List.of());
        when(jdbcTemplate.queryForList(SchemaController.COLUMNS_SQL)).thenReturn(List.of());

        mockMvc.perform(get("/api/schema"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.database").value("neurosql_db"))
                .andExpect(jsonPath("$.tableCount").value(0))
                .andExpect(jsonPath("$.schemas[0].name").value("public"))
                .andExpect(jsonPath("$.schemas[0].tables").isEmpty());
    }

    @Test
    void groupsTablesAndColumnsBySchemaAndPreservesColumnOrder() throws Exception {
        when(jdbcTemplate.queryForObject(eq("SELECT current_database()"), eq(String.class)))
                .thenReturn("neurosql_db");
        when(jdbcTemplate.queryForList(SchemaController.SCHEMAS_SQL))
                .thenReturn(List.of(Map.of("schema_name", "public")));
        when(jdbcTemplate.queryForList(SchemaController.TABLES_SQL)).thenReturn(List.of(
                Map.of("table_schema", "public", "table_name", "customers", "table_type", "BASE TABLE")));
        when(jdbcTemplate.queryForList(SchemaController.COLUMNS_SQL)).thenReturn(List.of(
                Map.of("table_schema", "public", "table_name", "customers", "column_name", "id",
                        "data_type", "integer", "is_nullable", "NO", "ordinal_position", 1),
                Map.of("table_schema", "public", "table_name", "customers", "column_name", "email",
                        "data_type", "character varying", "is_nullable", "YES", "ordinal_position", 2)));

        mockMvc.perform(get("/api/schema"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tableCount").value(1))
                .andExpect(jsonPath("$.schemas[0].tables[0].name").value("customers"))
                .andExpect(jsonPath("$.schemas[0].tables[0].type").value("BASE TABLE"))
                .andExpect(jsonPath("$.schemas[0].tables[0].columns[0].name").value("id"))
                .andExpect(jsonPath("$.schemas[0].tables[0].columns[0].nullable").value(false))
                .andExpect(jsonPath("$.schemas[0].tables[0].columns[1].name").value("email"))
                .andExpect(jsonPath("$.schemas[0].tables[0].columns[1].nullable").value(true));
    }
}
