package com.neurosql.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReadOnlySqlValidatorTest {
    private final ReadOnlySqlValidator validator = new ReadOnlySqlValidator();

    @Test
    void acceptsSimpleSelectWithoutExecutingIt() {
        var result = validator.validate("SELECT id, name FROM customers WHERE active = true;");

        assertThat(result.valid()).isTrue();
        assertThat(result.statementType()).isEqualTo("SELECT");
        assertThat(result.statementCount()).isEqualTo(1);
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).isNotEmpty();
    }

    @Test
    void acceptsWithQueryWhenAllTokensAreReadOnly() {
        var result = validator.validate("WITH recent AS (SELECT id FROM orders) SELECT id FROM recent");

        assertThat(result.valid()).isTrue();
        assertThat(result.statementType()).isEqualTo("WITH");
    }

    @Test
    void rejectsMutationKeywordsEvenInsideCte() {
        var result = validator.validate("WITH changed AS (DELETE FROM customers RETURNING id) SELECT id FROM changed");

        assertThat(result.valid()).isFalse();
        assertThat(result.statementCount()).isEqualTo(1);
        assertThat(result.errors()).anyMatch(message -> message.contains("DELETE"));
    }

    @Test
    void rejectsMultipleStatementsButAllowsTrailingSemicolon() {
        var result = validator.validate("SELECT 1; SELECT 2");

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(message -> message.contains("one SQL statement"));
    }

    @Test
    void ignoresDangerousWordsInsideStringsAndComments() {
        var result = validator.validate("SELECT 'DELETE' AS label /* DROP TABLE ignored */ -- UPDATE ignored\nFROM customers");

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsSideEffectingFunctionsAndSelectInto() {
        assertThat(validator.validate("SELECT nextval('orders_id_seq')").valid()).isFalse();
        assertThat(validator.validate("SELECT id INTO new_table FROM customers").valid()).isFalse();
    }

    @Test
    void rejectsUnterminatedLiteralAndEmptyInput() {
        assertThat(validator.validate("SELECT 'unfinished").valid()).isFalse();
        assertThat(validator.validate("   ").valid()).isFalse();
    }

    @Test
    void doesNotLetBackslashHideStatementBoundariesInStandardStrings() {
        var result = validator.validate("SELECT 'x\\'; DELETE FROM customers; SELECT 'y'");

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(message -> message.contains("one SQL statement"));
    }
}
