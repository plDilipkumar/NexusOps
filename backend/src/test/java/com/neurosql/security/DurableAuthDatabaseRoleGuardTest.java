package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class DurableAuthDatabaseRoleGuardTest {
    @Mock
    private JdbcTemplate jdbcTemplate;

    private DurableAuthDatabaseRoleGuard guard;

    @BeforeEach
    void setUp() {
        guard = new DurableAuthDatabaseRoleGuard(jdbcTemplate);
    }

    @Test
    void acceptsOnlyTheVerifiedDedicatedRuntimeIdentity() {
        when(jdbcTemplate.queryForObject(
                DurableAuthDatabaseRoleGuard.VALIDATE_RUNTIME_IDENTITY_SQL, Boolean.class))
                .thenReturn(Boolean.TRUE);

        assertThatCode(() -> guard.run(null)).doesNotThrowAnyException();

        verify(jdbcTemplate).queryForObject(
                DurableAuthDatabaseRoleGuard.VALIDATE_RUNTIME_IDENTITY_SQL, Boolean.class);
    }

    @Test
    void refusesOwnerOrOverPrivilegedRuntimeIdentity() {
        when(jdbcTemplate.queryForObject(
                DurableAuthDatabaseRoleGuard.VALIDATE_RUNTIME_IDENTITY_SQL, Boolean.class))
                .thenReturn(Boolean.FALSE);

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dedicated")
                .hasMessageContaining("neurosql_auth_runtime");
    }

    @Test
    void refusesToStartWhenTheRuntimeRoleOrSchemaIsMissing() {
        when(jdbcTemplate.queryForObject(
                DurableAuthDatabaseRoleGuard.VALIDATE_RUNTIME_IDENTITY_SQL, Boolean.class))
                .thenThrow(new EmptyResultDataAccessException(1));

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database owner is not a runtime identity");
    }
}
