package com.neurosql.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Conservative lexical guardrail for SQL drafts. It does not parse or execute SQL
 * and must not be treated as a substitute for a read-only database role.
 */
@Component
public class ReadOnlySqlValidator {
    private static final int MAX_SQL_LENGTH = 10_000;
    private static final Set<String> BLOCKED_WORDS = Set.of(
            "INSERT", "UPDATE", "DELETE", "MERGE", "CREATE", "ALTER", "DROP",
            "TRUNCATE", "GRANT", "REVOKE", "COPY", "CALL", "DO", "VACUUM",
            "ANALYZE", "SET", "RESET", "BEGIN", "COMMIT", "ROLLBACK",
            "SAVEPOINT", "RELEASE", "LOCK", "REFRESH", "IMPORT", "EXPORT",
            "INTO");
    private static final Set<String> BLOCKED_FUNCTIONS = Set.of(
            "NEXTVAL", "SETVAL", "SET_CONFIG", "PG_ADVISORY_LOCK",
            "PG_ADVISORY_XACT_LOCK", "PG_TRY_ADVISORY_LOCK",
            "PG_TRY_ADVISORY_XACT_LOCK", "PG_CANCEL_BACKEND",
            "PG_TERMINATE_BACKEND", "PG_RELOAD_CONF", "PG_ROTATE_LOGFILE",
            "PG_NOTIFY", "PG_SWITCH_WAL", "PG_CREATE_RESTORE_POINT",
            "LO_IMPORT", "LO_EXPORT", "DBLINK_EXEC", "PG_WRITE_FILE",
            "PG_READ_FILE", "PG_READ_BINARY_FILE", "PG_LS_DIR");

    public ValidationResponse validate(String sql) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = List.of(
                "This is a conservative lexical check, not a PostgreSQL parser; it does not execute SQL.",
                "Before any future execution, use a least-privileged database role with read-only transaction enforcement.");

        if (sql == null || sql.isBlank()) {
            errors.add("Enter a SQL statement.");
            return response(false, null, errors, warnings);
        }
        if (sql.length() > MAX_SQL_LENGTH) {
            errors.add("SQL exceeds the 10000-character validation limit.");
            return response(false, null, errors, warnings);
        }

        Tokenization tokenization = tokenize(sql);
        if (tokenization.malformed()) {
            errors.add("SQL contains an unterminated string, quoted identifier, dollar-quoted string, or block comment.");
            return response(false, null, errors, warnings);
        }
        if (tokenization.multipleStatements()) {
            errors.add("Only one SQL statement is allowed.");
            return response(false, null, errors, warnings);
        }
        List<String> tokens = tokenization.words();
        if (tokens.isEmpty()) {
            errors.add("Enter a SQL statement.");
            return response(false, null, errors, warnings);
        }

        String first = tokens.get(0);
        if (!first.equals("SELECT") && !first.equals("WITH")) {
            errors.add("Only SELECT or WITH queries are accepted.");
        }
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (BLOCKED_WORDS.contains(token)) {
                errors.add("Blocked keyword detected: " + token + ".");
            }
            if (BLOCKED_FUNCTIONS.contains(token)) {
                errors.add("Potentially side-effecting function is not allowed: " + token + ".");
            }
            if ((token.equals("SHARE") || token.equals("UPDATE")) && i > 0 && tokens.get(i - 1).equals("FOR")) {
                errors.add("Row-locking clauses are not allowed.");
            }
        }

        return response(errors.isEmpty(), first, errors, warnings);
    }

    private static ValidationResponse response(
            boolean valid, String statementType, List<String> errors, List<String> warnings) {
        return new ValidationResponse(valid, statementType, statementType == null ? 0 : 1,
                List.copyOf(errors), warnings);
    }

    private static Tokenization tokenize(String sql) {
        List<String> words = new ArrayList<>();
        boolean terminated = false;
        boolean multipleStatements = false;
        int i = 0;

        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < sql.length() && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int depth = 1;
                i += 2;
                while (i < sql.length() && depth > 0) {
                    if (i + 1 < sql.length() && sql.charAt(i) == '/' && sql.charAt(i + 1) == '*') {
                        depth++;
                        i += 2;
                    } else if (i + 1 < sql.length() && sql.charAt(i) == '*' && sql.charAt(i + 1) == '/') {
                        depth--;
                        i += 2;
                    } else {
                        i++;
                    }
                }
                if (depth != 0) {
                    return new Tokenization(words, true, false);
                }
                continue;
            }
            if (c == ';') {
                if (terminated) {
                    multipleStatements = true;
                }
                terminated = true;
                i++;
                continue;
            }
            if (terminated) {
                multipleStatements = true;
            }
            if (c == '\'' || c == '"') {
                char quote = c;
                boolean escapeBackslash = quote == '\'' && i > 0
                        && (sql.charAt(i - 1) == 'E' || sql.charAt(i - 1) == 'e')
                        && (i < 2 || !Character.isLetterOrDigit(sql.charAt(i - 2)) && sql.charAt(i - 2) != '_');
                i++;
                boolean closed = false;
                while (i < sql.length()) {
                    char current = sql.charAt(i++);
                    if (current == '\\' && escapeBackslash && i < sql.length()) {
                        i++;
                    } else if (current == quote) {
                        if (i < sql.length() && sql.charAt(i) == quote) {
                            i++;
                        } else {
                            closed = true;
                            break;
                        }
                    }
                }
                if (!closed) {
                    return new Tokenization(words, true, false);
                }
                continue;
            }
            if (c == '$') {
                int delimiterEnd = dollarDelimiterEnd(sql, i);
                if (delimiterEnd > i) {
                    String delimiter = sql.substring(i, delimiterEnd);
                    int close = sql.indexOf(delimiter, delimiterEnd);
                    if (close < 0) {
                        return new Tokenization(words, true, false);
                    }
                    i = close + delimiter.length();
                    continue;
                }
            }
            if (Character.isLetter(c) || c == '_') {
                int start = i++;
                while (i < sql.length()) {
                    char current = sql.charAt(i);
                    if (!Character.isLetterOrDigit(current) && current != '_' && current != '$') {
                        break;
                    }
                    i++;
                }
                words.add(sql.substring(start, i).toUpperCase(Locale.ROOT));
                continue;
            }
            i++;
        }
        return new Tokenization(words, false, multipleStatements);
    }

    private static int dollarDelimiterEnd(String sql, int start) {
        int i = start + 1;
        while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) {
            i++;
        }
        if (i < sql.length() && sql.charAt(i) == '$') {
            return i + 1;
        }
        return start;
    }

    private record Tokenization(List<String> words, boolean malformed, boolean multipleStatements) {}

    public record ValidationResponse(
            boolean valid, String statementType, int statementCount, List<String> errors, List<String> warnings) {}
}
