package com.harnex.conversation;

import java.util.List;

/** A business commit identity, independent of filesystem paths or database transactions. */
public record CommitReceipt(String operationId, String conversationId, String turnId, String runId,
    List<String> messageIds, long historyRevision, Kind kind) {
    public CommitReceipt { messageIds = List.copyOf(messageIds); }
    public enum Kind { TURN_ACCEPTED, ANSWER_CHECKPOINT, AGENT_STEP_COMMITTED, RESULT_COMMITTED,
        MESSAGE_REVISED, BRANCH_SELECTED, CONVERSATION_FORKED, METADATA_UPDATED, CONVERSATION_DELETED, EXECUTION_RECORD_COMMITTED, RUN_STATE_UPDATED, RUN_INPUT_COMMITTED }
}
