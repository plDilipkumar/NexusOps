package com.neurosql.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.Test;

class SqlExecutionConfigurationTest {
    @Test
    void refusesToStartWithoutASeparatePostgresSqlEndpoint() {
        assertThatThrownBy(() -> SqlExecutionConfiguration.readOnlySqlPoolConfig("", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NEUROSQL_SQL_JDBC_URL");
    }

    @Test
    void refusesToStartWithoutDedicatedSqlLoginCredentials() {
        assertThatThrownBy(() -> SqlExecutionConfiguration.readOnlySqlPoolConfig(
                "jdbc:postgresql://localhost:5432/neurosql_db", "", "test-only"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NEUROSQL_SQL_DATABASE_USERNAME");

        assertThatThrownBy(() -> SqlExecutionConfiguration.readOnlySqlPoolConfig(
                "jdbc:postgresql://localhost:5432/neurosql_db", "neurosql_sql_readonly", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NEUROSQL_SQL_DATABASE_PASSWORD");
    }

    @Test
    void configuresASmallReadOnlyPoolForTheDedicatedLogin() {
        HikariConfig config = SqlExecutionConfiguration.readOnlySqlPoolConfig(
                "jdbc:postgresql://localhost:5432/neurosql_db?sslmode=require",
                "neurosql_sql_readonly",
                "test-only-placeholder");

        assertThat(config.getJdbcUrl()).startsWith("jdbc:postgresql://");
        assertThat(config.getUsername()).isEqualTo("neurosql_sql_readonly");
        assertThat(config.isReadOnly()).isTrue();
        assertThat(config.getMaximumPoolSize()).isEqualTo(3);
        assertThat(config.getMinimumIdle()).isZero();
        assertThat(config.getConnectionTimeout()).isEqualTo(5_000);
        assertThat(config.getPoolName()).isEqualTo("neurosql-readonly-sql");
    }

    @Test
    void rejectsNonPostgresJdbcUrls() {
        assertThatThrownBy(() -> SqlExecutionConfiguration.readOnlySqlPoolConfig(
                "jdbc:h2:mem:unexpected", "neurosql_sql_readonly", "test-only"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PostgreSQL");
    }
}
