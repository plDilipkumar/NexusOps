package com.neurosql.api;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * Registers SQL execution only when explicitly enabled.
 *
 * <p>SQL execution deliberately uses a separate, read-only connection pool.
 * The application's default datasource remains the control-plane connection
 * for authentication, sessions, workspace membership, and future query history.
 * This prevents a read-only SQL identity from being reused for application writes,
 * and prevents an application-owner identity from executing user SQL.</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "neurosql.sql-execution", name = "enabled", havingValue = "true")
public class SqlExecutionConfiguration {
    @Bean
    ActiveQueryRegistry activeQueryRegistry() {
        return new ActiveQueryRegistry();
    }

    @Bean(destroyMethod = "close")
    ReadOnlyQueryExecutor readOnlyQueryExecutor(
            ReadOnlySqlValidator validator,
            @Value("${neurosql.auth.expected-database:neurosql_db}") String expectedDatabase,
            @Value("${neurosql.sql-execution.expected-role:neurosql_sql_readonly}") String expectedReadOnlyRole,
            @Value("${neurosql.sql-execution.jdbc-url:}") String jdbcUrl,
            @Value("${neurosql.sql-execution.username:}") String username,
            @Value("${neurosql.sql-execution.password:}") String password,
            @Value("${neurosql.sql-execution.max-rows:500}") int maximumRows,
            @Value("${neurosql.sql-execution.timeout-seconds:5}") int queryTimeoutSeconds) {
        HikariDataSource sqlDataSource = new HikariDataSource(
                readOnlySqlPoolConfig(jdbcUrl, username, password));
        try {
            return new ReadOnlyQueryExecutor(
                    new JdbcTemplate(sqlDataSource),
                    new DataSourceTransactionManager(sqlDataSource),
                    validator,
                    expectedDatabase,
                    expectedReadOnlyRole,
                    maximumRows,
                    queryTimeoutSeconds,
                    sqlDataSource);
        } catch (RuntimeException failure) {
            sqlDataSource.close();
            throw failure;
        }
    }

    static HikariConfig readOnlySqlPoolConfig(String jdbcUrl, String username, String password) {
        if (jdbcUrl == null || !jdbcUrl.trim().startsWith("jdbc:postgresql://")) {
            throw new IllegalStateException(
                    "NEUROSQL_SQL_JDBC_URL must be a dedicated PostgreSQL JDBC URL.");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalStateException(
                    "NEUROSQL_SQL_DATABASE_USERNAME must identify the dedicated read-only login.");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "NEUROSQL_SQL_DATABASE_PASSWORD must be configured for the dedicated read-only login.");
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl.trim());
        config.setUsername(username.trim());
        config.setPassword(password);
        config.setReadOnly(true);
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5_000);
        config.setPoolName("neurosql-readonly-sql");
        return config;
    }
}
