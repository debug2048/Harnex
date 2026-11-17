package com.harnex.conversation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.harnex.execution.ExecutionSpec;

import io.swagger.v3.oas.annotations.media.Schema;

/** Business persistence boundary. All scope arguments come from the authenticated Runtime, never a model. */
public interface ConversationRepository {
    static String agentStepMessageId(String runId, String stepId) {
        try {
            return "step_" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((runId + "\0" + stepId).getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    SummaryPage listSummaries(String scopeId, String cursor, int limit);
    Optional<ConversationMeta> loadConversation(String scopeId, String conversationId);
    MessagePage pageMessages(String scopeId, String conversationId, String leafId, String beforeMessageId, int limit);
    Optional<MessageRecord> findMessage(String scopeId, String conversationId, String messageId);
    /** Body-free metadata where supported; ancestry is always scoped to the explicit leaf, including itself. */
    default Optional<MessageHeader> findMessageHeader(String scopeId, String conversationId, String messageId) {
        return findMessage(scopeId, conversationId, messageId).map(MessageHeader::from);
    }
    default boolean isMessageAncestor(String scopeId, String conversationId, String ancestorMessageId, String leafId) {
        if (ancestorMessageId == null || leafId == null) return false;
        Set<String> seen = new HashSet<>();
        for (String id = leafId; id != null;) {
            if (!seen.add(id)) throw new ConversationException("history_corrupt", "Message parent cycle");
            MessageHeader node = findMessageHeader(scopeId, conversationId, id).orElse(null);
            if (node == null) return false;
            if (ancestorMessageId.equals(id)) return true;
            id = node.parentMessageId();
        }
        return false;
    }
    default Optional<MessageHeader> findBranchChild(String scopeId, String conversationId, String ancestorMessageId, String leafId) {
        if (ancestorMessageId == null || leafId == null) return Optional.empty();
        Set<String> seen = new HashSet<>();
        MessageHeader child = null;
        for (String id = leafId; id != null;) {
            if (!seen.add(id)) throw new ConversationException("history_corrupt", "Message parent cycle");
            MessageHeader node = findMessageHeader(scopeId, conversationId, id).orElse(null);
            if (node == null) return Optional.empty();
            if (ancestorMessageId.equals(id)) return Optional.ofNullable(child);
            child = node; id = node.parentMessageId();
        }
        return Optional.empty();
    }
    default boolean hasCompletedRun(String scopeId, String conversationId, String runId, String leafId) {
        if (runId == null || leafId == null) return false;
        Set<String> seen = new HashSet<>();
        for (String id = leafId; id != null;) {
            if (!seen.add(id)) throw new ConversationException("history_corrupt", "Message parent cycle");
            MessageHeader node = findMessageHeader(scopeId, conversationId, id).orElse(null);
            if (node == null) return false;
            if (runId.equals(node.runId()) && node.isFinalAnswer()) return true;
            id = node.parentMessageId();
        }
        return false;
    }
    Optional<TurnRecord> findTurn(String scopeId, String conversationId, String turnId);
    RunHistory captureRunHistory(String scopeId, String conversationId, String turnId, String runId);
    CommitReceipt acceptTurn(AcceptTurn command);
    CommitReceipt acceptRetry(RetryTurn command);
    CommitReceipt checkpointAnswer(WriteResult command);
    CommitReceipt commitAgentStepResult(AgentStepResult command);
    CommitReceipt appendRunInput(AppendRunInput command);
    CommitReceipt commitTurnResult(WriteResult command);
    CommitReceipt reviseMessage(ReviseMessage command);
    CommitReceipt selectBranch(SelectBranch command);
    CommitReceipt forkConversation(ForkConversation command);
    CommitReceipt updateTitle(UpdateTitle command);
    CommitReceipt recordRunState(RunState command);
    CommitReceipt deleteConversation(DeleteConversation command);
    CommitReceipt saveExecutionRecord(String scopeId, String operationId, ExecutionSearchRecord record);
    Optional<ExecutionSearchRecord> findExecutionRecord(String scopeId, String conversationId, String recordId);
    Optional<CommitReceipt> findOperation(String scopeId, String operationId);
    ChangePage readCommittedChanges(String scopeId, long cursor, int limit);

    record AcceptTurn(String scopeId, String conversationId, String operationId, String turnId, String runId,
        String requestId, String requestHash, Long expectedHistoryRevision, String parentMessageId,
        String predecessorTurnId, List<ContentPart> input, ExecutionSpec execution) {
        public AcceptTurn { input = List.copyOf(input); }
    }
    record WriteResult(String scopeId, String conversationId, String operationId, String turnId, String runId,
        List<ContentPart> contentParts, MessageRecord.Status status) {
        public WriteResult { contentParts = List.copyOf(contentParts); }
    }
    record RetryTurn(String scopeId, String conversationId, String operationId, String turnId, String runId,
        String requestId, String requestHash, long expectedHistoryRevision, String userMessageId, ExecutionSpec execution) {}
    record AgentStepResult(String scopeId, String conversationId, String operationId, String turnId, String runId,
        String stepId, List<ContentPart> contentParts) {
        public AgentStepResult { contentParts = List.copyOf(contentParts); }
    }
    record AppendRunInput(String scopeId, String conversationId, String operationId, String turnId, String runId,
        String inputId, List<ContentPart> contentParts) {
        public AppendRunInput { contentParts = List.copyOf(contentParts); }
    }
    record ReviseMessage(String scopeId, String conversationId, String operationId, String messageId,
        long expectedHistoryRevision, List<ContentPart> contentParts) {
        public ReviseMessage { contentParts = List.copyOf(contentParts); }
    }
    record SelectBranch(String scopeId, String conversationId, String operationId, String leafId,
        long expectedHistoryRevision) {}
    record ForkConversation(String scopeId, String conversationId, String operationId, String sourceConversationId,
        String sourceLeafId, String title) {}
    record UpdateTitle(String scopeId, String conversationId, String operationId, String title,
        long expectedMetadataRevision) {}
    record RunState(String scopeId, String conversationId, String operationId, String runId, String status) {}
    record DeleteConversation(String scopeId, String conversationId, String operationId, long expectedHistoryRevision) {}
    @Schema(requiredProperties = {"items"})
    record SummaryPage(List<ConversationMeta> items, String nextCursor) {}
    record RunHistory(ConversationMeta conversation, TurnRecord turn, MessageRecord answer) {}
    record MessageHeader(String messageId, String parentMessageId, String runId, MessageRecord.Role role,
        MessageRecord.Status status, long revision) {
        public static MessageHeader from(MessageRecord message) {
            return new MessageHeader(message.messageId(), message.parentMessageId(), message.runId(), message.role(),
                message.status(), message.revision());
        }
        @JsonIgnore
        public boolean isMutable() { return status == MessageRecord.Status.PENDING || status == MessageRecord.Status.STREAMING; }
        @JsonIgnore
        public boolean isFinalAnswer() {
            return role == MessageRecord.Role.ASSISTANT && !isMutable() && status != MessageRecord.Status.PROGRESS;
        }
    }
    /** Newest first; nextCursor identifies the last returned message, not an array offset. */
    @Schema(requiredProperties = {"items", "historyRevision"})
    record MessagePage(List<MessageRecord> items, @Schema(nullable = true) String nextCursor,
        long historyRevision, @Schema(nullable = true) String activeLeafId) {}
    record CommittedChange(long sequence, CommitReceipt receipt, List<String> executionRecordIds) {}
    record ChangePage(List<CommittedChange> items, long nextCursor, boolean hasMore) {}
}
