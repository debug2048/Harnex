package com.harnex.session;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.harnex.model.ConversationThread;

public interface SessionStore {
    void save(ConversationThread t);

    Optional<ConversationThread> get(String id);

    List<ConversationThread> list(int offset, int limit);

    boolean delete(String id);

    /** Full-text search title + message content. */
    List<SearchHit> search(String query, int limit);

    /**
     * Register a listener invoked with the thread id whenever a thread is deleted via {@link #delete(String)}. Default
     * no-op so non-FTS implementations keep working. Callers (ApprovalService, SessionHooks, TodoWriteTool purge)
     * should use this hook instead of casting to a concrete store implementation.
     */
    default void onThreadDeleted(Consumer<String> listener) {
        // No-op default for stores that don't broadcast deletion events.
    }

    /**
     * Whether the given thread id was deleted recently and a subsequent {@link #save} would be refused as a tombstoned
     * resurrection. Lets pre-loop entry points (gateway, snapshot restore, future schedulers) skip a wasted run instead
     * of producing a reply that silently fails to persist. Default {@code false} so non-tombstoning stores stay opaque.
     */
    default boolean isDeletedRecently(String id) {
        return false;
    }

    record SearchHit(String threadId, String title, String fullTitle, String snippet, float score) {
    }
}
