package com.harnex.conversation;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.swagger.v3.oas.annotations.media.Schema;

/** Search engines return domain hits; history remains the authority for scope and deletion. */
public interface ConversationSearch {
    SearchPage search(String scopeId, String query, String conversationId, String cursor, int limit);
    record Hit(String conversationId, String messageId, String runId, String toolCallId, String sourceKind,
        String title, String snippet, long sourceRevision, String executionRecordId) {}
    /** Item cursors resume after the exact raw hit even when a source is subsequently revised or removed. */
    record SearchPage(List<Hit> items, String nextCursor, State state, long indexedThrough,
        @JsonIgnore @Schema(hidden = true) String replayCursor,
        @JsonIgnore @Schema(hidden = true) List<String> itemCursors) {
        public SearchPage(List<Hit> items, String nextCursor, State state, long indexedThrough) {
            this(items, nextCursor, state, indexedThrough, null, List.of());
        }
    }
    enum State { READY, CATCHING_UP, REBUILDING, UNAVAILABLE }
}
