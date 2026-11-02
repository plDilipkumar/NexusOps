package com.neurosql.security;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

/**
 * Returns bounded, keyset-paginated history for exactly one authenticated
 * user/workspace pair. It deliberately does not expose workspace-wide history.
 */
@Service
@ConditionalOnExpression(
        "'${neurosql.auth.durable-sessions-enabled:false}' == 'true' and "
                + "'${neurosql.auth.shared-rate-limit-enabled:false}' == 'true' and "
                + "'${neurosql.sql-history.enabled:false}' == 'true'")
public class QueryHistoryReadService {
    static final int DEFAULT_PAGE_SIZE = 25;
    static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_OUTCOME_LENGTH = 16;
    private static final Set<String> OUTCOMES = Set.of("SUCCEEDED", "FAILED", "CANCELED");

    private final JdbcQueryHistoryRepository repository;

    public QueryHistoryReadService(JdbcQueryHistoryRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public QueryHistoryPage list(
            UUID userId,
            UUID workspaceId,
            int pageSize,
            String cursor,
            String rawOutcome) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("History page size must be between 1 and 100.");
        }

        String outcome = normalizeOutcome(rawOutcome);
        QueryHistoryCursor.Position before = cursor == null
                ? null
                : QueryHistoryCursor.decode(cursor, workspaceId, userId, outcome);
        List<JdbcQueryHistoryRepository.HistoryEntry> fetched =
                repository.findRecent(workspaceId, userId, pageSize + 1, before, outcome);

        boolean hasMore = fetched.size() > pageSize;
        List<JdbcQueryHistoryRepository.HistoryEntry> items = hasMore
                ? List.copyOf(fetched.subList(0, pageSize))
                : List.copyOf(fetched);

        String nextCursor = null;
        if (hasMore && !items.isEmpty()) {
            JdbcQueryHistoryRepository.HistoryEntry last = items.get(items.size() - 1);
            nextCursor = QueryHistoryCursor.encode(
                    workspaceId, userId, last.createdAt(), last.historyId(), outcome);
        }
        return new QueryHistoryPage(items, nextCursor, hasMore);
    }

    private static String normalizeOutcome(String rawOutcome) {
        if (rawOutcome == null) {
            return null;
        }
        if (rawOutcome.length() > MAX_OUTCOME_LENGTH) {
            throw new IllegalArgumentException("History outcome filter is invalid.");
        }
        String value = rawOutcome.strip();
        if (value.isEmpty() || "ALL".equalsIgnoreCase(value)) {
            return null;
        }
        String normalized = value.toUpperCase(Locale.ROOT);
        if (!OUTCOMES.contains(normalized)) {
            throw new IllegalArgumentException("History outcome filter is invalid.");
        }
        return normalized;
    }

    public record QueryHistoryPage(
            List<JdbcQueryHistoryRepository.HistoryEntry> items,
            String nextCursor,
            boolean hasMore) {
        public QueryHistoryPage {
            items = List.copyOf(Objects.requireNonNull(items, "items"));
            if (hasMore != (nextCursor != null)) {
                throw new IllegalArgumentException("History pagination metadata is inconsistent.");
            }
        }
    }
}
