package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.aop.support.AopUtils;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = RegistrationServicePostgresIntegrationTest.TestConfig.class)
@EnabledIfEnvironmentVariable(
        named = "NEUROSQL_TEST_JDBC_URL",
        matches = "^jdbc:postgresql://(localhost|127\\\\.0\\\\.0\\\\.1)(:[0-9]+)?/neurosql_test(\\\\?.*)?$")
class RegistrationServicePostgresIntegrationTest {
    private static final String PASSWORD = "correct-horse-12";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RegistrationService registrationService;

    @Autowired
    private AccountActionTokenService actionTokens;

    @BeforeEach
    void setUpDisposablePostgresFixture() {
        reset(actionTokens);
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
        jdbc.execute("ALTER TABLE neurosql_app.app_users ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now()");
        jdbc.execute("ALTER TABLE neurosql_app.app_users ALTER COLUMN user_id SET DEFAULT gen_random_uuid()");
        jdbc.execute("ALTER TABLE neurosql_app.app_users ALTER COLUMN status SET DEFAULT 'ACTIVE'");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_app_users_email_lower ON neurosql_app.app_users (lower(email))");
    }

    @Test
    void accountAndVerificationTokenAreOrchestratedInsideTransactionalService() {
        assertThat(AopUtils.isAopProxy(registrationService)).isTrue();
        String email = "registration-" + UUID.randomUUID() + "@example.test";
        String rawToken = "R".repeat(43);
        AccountActionTokenService.IssuedActionToken issued =
                new AccountActionTokenService.IssuedActionToken(
                        rawToken,
                        AccountActionTokenService.Purpose.EMAIL_VERIFICATION,
                        Instant.parse("2026-10-30T12:00:00Z"));
        when(actionTokens.issueToken(any(UUID.class), eq(AccountActionTokenService.Purpose.EMAIL_VERIFICATION)))
                .thenReturn(issued);

        RegistrationService.RegistrationAttempt result =
                registrationService.register(email, PASSWORD, " Registration Test ");

        assertThat(result.verificationDelivery()).contains(issued);
        assertThat(result.toString()).doesNotContain(rawToken);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM neurosql_app.app_users WHERE email = ? AND email_verified_at IS NULL",
                Integer.class,
                email)).isEqualTo(1);
    }

    @Test
    void tokenIssuanceFailureRollsBackNewAccount() {
        assertThat(AopUtils.isAopProxy(registrationService)).isTrue();
        String email = "rollback-" + UUID.randomUUID() + "@example.test";
        when(actionTokens.issueToken(any(UUID.class), eq(AccountActionTokenService.Purpose.EMAIL_VERIFICATION)))
                .thenThrow(new IllegalStateException("simulated token persistence failure"));

        assertThatThrownBy(() -> registrationService.register(email, PASSWORD, "Rollback Test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated token persistence failure");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM neurosql_app.app_users WHERE email = ?",
                Integer.class,
                email)).isZero();
    }

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName("org.postgresql.Driver");
            dataSource.setUrl(System.getenv("NEUROSQL_TEST_JDBC_URL"));
            dataSource.setUsername(System.getenv("NEUROSQL_TEST_DB_USERNAME"));
            dataSource.setPassword(System.getenv("NEUROSQL_TEST_DB_PASSWORD"));
            return dataSource;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        JdbcAccountRepository accountRepository(JdbcTemplate jdbcTemplate) {
            return new JdbcAccountRepository(jdbcTemplate);
        }

        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder(4);
        }

        @Bean
        AccountActionTokenService accountActionTokenService() {
            return Mockito.mock(AccountActionTokenService.class);
        }

        @Bean
        RegistrationService registrationService(
                JdbcAccountRepository accounts,
                PasswordEncoder passwordEncoder,
                AccountActionTokenService actionTokens) {
            return new RegistrationService(accounts, passwordEncoder, actionTokens);
        }
    }
}
