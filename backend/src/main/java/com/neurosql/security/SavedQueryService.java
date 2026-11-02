package com.neurosql.security;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Validates and manages the initial workspace-scoped saved-query operations.
 * Listing is shared with authorized workspace members; creation attributes the
 * record to the authenticated durable-session user.
 */
@Service
@ConditionalOnExpression(
        "'${neurosql.auth.durable-sessions-enabled:false}' == 'true' and "
                + "'${neurosql.auth.shared-rate-limit-enabled:false}' == 'true' and "
                + "'${neurosql.saved-queries.enabled:false}' == 'true'")
public class SavedQueryService {
    static final int DEFAULT_PAGE_SIZE = 25;
    static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_NAME_CODE_POINTS = 120;
    private static final int MAX_DESCRIPTION_CODE_POINTS = 500;
    private static final int MAX_QUERY_CODE_POINTS = 50_000;

    private final JdbcSavedQueryRepository repository;

    public SavedQueryService(JdbcSavedQueryRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public SavedQueryPage list(UUID workspaceId, int pageSize, String cursor) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Saved-query page size must be between 1 and 100.");
        }

        SavedQueryCursor.Position before = cursor == null
                ? null
                : SavedQueryCursor.decode(cursor, workspaceId);
        List<JdbcSavedQueryRepository.SavedQueryEntry> fetched =
                repository.findRecent(workspaceId, pageSize + 1, before);

        boolean hasMore = fetched.size() > pageSize;
        List<JdbcSavedQueryRepository.SavedQueryEntry> items = hasMore
                ? List.copyOf(fetched.subList(0, pageSize))
                : List.copyOf(fetched);

        String nextCursor = null;
        if (hasMore && !items.isEmpty()) {
            JdbcSavedQueryRepository.SavedQueryEntry last = items.get(items.size() - 1);
            nextCursor = SavedQueryCursor.encode(workspaceId, last.updatedAt(), last.savedQueryId());
        }
        return new SavedQueryPage(items, nextCursor, hasMore);
    }

    public JdbcSavedQueryRepository.SavedQueryEntry create(
            UUID workspaceId,
            UUID ownerUserId,
            String rawName,
            String rawDescription,
            String rawQueryText) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(ownerUserId, "ownerUserId");
        String name = normalizeName(rawName);
        String description = normalizeDescription(rawDescription);
        String queryText = normalizeQueryText(rawQueryText);

        try {
            return repository.insert(workspaceId, ownerUserId, name, description, queryText);
        } catch (DuplicateKeyException duplicate) {
            throw new DuplicateNameException();
        }
    }

    /**
     * Updates a saved query only inside the caller's already-authorized workspace.
     * The immutable owner and workspace keys are not accepted from the request.
     */
    public Optional<JdbcSavedQueryRepository.SavedQueryEntry> update(
            UUID workspaceId,
            UUID savedQueryId,
            String rawName,
            String rawDescription,
            String rawQueryText) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(savedQueryId, "savedQueryId");
        String name = normalizeName(rawName);
        String description = normalizeDescription(rawDescription);
        String queryText = normalizeQueryText(rawQueryText);

        try {
            return repository.update(workspaceId, savedQueryId, name, description, queryText);
        } catch (DuplicateKeyException duplicate) {
            throw new DuplicateNameException();
        }
    }

    /**
     * Returns false when the ID does not exist in the authorized workspace.
     */
    public boolean delete(UUID workspaceId, UUID savedQueryId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(savedQueryId, "savedQueryId");
        return repository.delete(workspaceId, savedQueryId);
    }

    private static String normalizeName(String value) {
        String name = value == null ? "" : value.strip();
        int length = name.codePointCount(0, name.length());
        if (length < 1 || length > MAX_NAME_CODE_POINTS
                || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Saved-query name must contain 1 to 120 printable characters.");
        }
        return name;
    }

    private static String normalizeDescription(String value) {
        String description = value == null ? "" : value.strip();
        if (description.codePointCount(0, description.length()) > MAX_DESCRIPTION_CODE_POINTS) {
            throw new IllegalArgumentException("Saved-query description must be 500 characters or fewer.");
        }
        return description;
    }

    private static String normalizeQueryText(String value) {
        String queryText = value == null ? "" : value.strip();
        int length = queryText.codePointCount(0, queryText.length());
        if (length < 1 || length > MAX_QUERY_CODE_POINTS) {
            throw new IllegalArgumentException("Saved SQL must contain between 1 and 50000 characters.");
        }
        return queryText;
    }

    public record SavedQueryPage(
            List<JdbcSavedQueryRepository.SavedQueryEntry> items,
            String nextCursor,
            boolean hasMore) {
        public SavedQueryPage {
            items = List.copyOf(Objects.requireNonNull(items, "items"));
            if (hasMore != (nextCursor != null)) {
                throw new IllegalArgumentException("Saved-query pagination metadata is inconsistent.");
            }
        }
    }

    public static final class DuplicateNameException extends RuntimeException {
        public DuplicateNameException() {
            super("A saved query with this name already exists in the workspace.");
        }
    }
}
