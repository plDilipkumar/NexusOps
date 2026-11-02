package com.neurosql.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Registers SQL execution only when explicitly enabled. The executor still
 * verifies the connected database role before every query.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "neurosql.sql-execution", name = "enabled", havingValue = "true")
public class SqlExecutionConfiguration {
    @Bean
    ReadOnlyQueryExecutor readOnlyQueryExecutor(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ReadOnlySqlValidator validator,
            @Value("${neurosql.auth.expected-database:neurosql_db}") String expectedDatabase,
            @Value("${neurosql.sql-execution.expected-role:neurosql_sql_readonly}") String expectedReadOnlyRole,
            @Value("${neurosql.sql-execution.max-rows:500}") int maximumRows,
            @Value("${neurosql.sql-execution.timeout-seconds:5}") int queryTimeoutSeconds) {
        return new ReadOnlyQueryExecutor(
                jdbc,
                transactionManager,
                validator,
                expectedDatabase,
                expectedReadOnlyRole,
                maximumRows,
                queryTimeoutSeconds);
    }
}
