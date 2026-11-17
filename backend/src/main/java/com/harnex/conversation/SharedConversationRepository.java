package com.harnex.conversation;

import java.util.Optional;
import java.util.function.Supplier;

import com.harnex.run.ProjectConversationLocks;

/** An explicit persistence decorator keeps the existing journal and its fault-recovery boundaries unchanged. */
final class SharedConversationRepository implements ConversationRepository {
    private final ProjectStorage storage;
    private final FileConversationRepository delegate;
    private final ProjectConversationLocks ownership;

    SharedConversationRepository(ProjectStorage storage, String scopeId, ProjectConversationLocks ownership) {
        this.storage = storage;
        this.ownership = ownership;
        delegate = storage.transaction(() -> new FileConversationRepository(storage.root(), scopeId));
    }
    private <T> T transaction(Supplier<T> action) {
        return storage.transaction(() -> { delegate.refreshSharedHead(); return action.get(); });
    }
    @Override public SummaryPage listSummaries(String scope, String cursor, int limit) {
        return transaction(() -> delegate.listSummaries(scope, cursor, limit));
    }
    @Override public Optional<ConversationMeta> loadConversation(String scope, String id) {
        return transaction(() -> delegate.loadConversation(scope, id));
    }
    @Override public MessagePage pageMessages(String scope, String id, String leaf, String before, int limit) {
        return transaction(() -> delegate.pageMessages(scope, id, leaf, before, limit));
    }
    @Override public Optional<MessageRecord> findMessage(String scope, String id, String message) {
        return transaction(() -> delegate.findMessage(scope, id, message));
    }
    @Override public Optional<MessageHeader> findMessageHeader(String scope, String id, String message) {
        return transaction(() -> delegate.findMessageHeader(scope, id, message));
    }
    @Override public boolean isMessageAncestor(String scope, String id, String ancestor, String leaf) {
        return transaction(() -> delegate.isMessageAncestor(scope, id, ancestor, leaf));
    }
    @Override public Optional<MessageHeader> findBranchChild(String scope, String id, String ancestor, String leaf) {
        return transaction(() -> delegate.findBranchChild(scope, id, ancestor, leaf));
    }
    @Override public boolean hasCompletedRun(String scope, String id, String run, String leaf) {
        return transaction(() -> delegate.hasCompletedRun(scope, id, run, leaf));
    }
    @Override public Optional<TurnRecord> findTurn(String scope, String id, String turn) {
        return transaction(() -> delegate.findTurn(scope, id, turn));
    }
    @Override public RunHistory captureRunHistory(String scope, String id, String turn, String run) {
        return transaction(() -> delegate.captureRunHistory(scope, id, turn, run));
    }
    @Override public CommitReceipt acceptTurn(AcceptTurn command) {
        return transaction(() -> delegate.acceptTurn(command, recoverInterruptedParent(command)));
    }

    /** A new owner can continue a checkpoint left by a crashed host, without re-executing its tools. */
    private Long recoverInterruptedParent(AcceptTurn command) {
        Long expected = command.expectedHistoryRevision();
        if (!storage.shared() || command.parentMessageId() == null || command.expectedHistoryRevision() == null
            || !ownership.ownsRun(command.conversationId(), command.runId())) return expected;
        var meta = delegate.loadConversation(command.scopeId(), command.conversationId()).orElse(null);
        if (meta == null || !command.parentMessageId().equals(meta.activeLeafId())) return expected;
        var parent = delegate.findMessage(command.scopeId(), command.conversationId(), command.parentMessageId()).orElse(null);
        if (parent == null || parent.runId() == null || !parent.runId().equals(meta.latestRunId())) return expected;
        String operation = "interrupted-parent:" + parent.runId() + ":" + parent.messageId();
        CommitReceipt recovery;
        if (parent.isMutable() && meta.historyRevision() == command.expectedHistoryRevision()) {
            // The new Run already holds the exclusive conversation lease: the previous writer cannot still run.
            recovery = delegate.commitTurnResult(new WriteResult(command.scopeId(), command.conversationId(), operation,
                parent.turnId(), parent.runId(), parent.contentParts(), MessageRecord.Status.INTERRUPTED));
        } else {
            recovery = delegate.findOperation(command.scopeId(), operation).orElse(null);
            if (recovery == null || meta.historyRevision() != recovery.historyRevision()
                || command.expectedHistoryRevision() + 1 != recovery.historyRevision()) return expected;
        }
        return recovery.historyRevision();
    }
    @Override public CommitReceipt acceptRetry(RetryTurn command) { return transaction(() -> delegate.acceptRetry(command)); }
    @Override public CommitReceipt checkpointAnswer(WriteResult command) { return transaction(() -> delegate.checkpointAnswer(command)); }
    @Override public CommitReceipt commitAgentStepResult(AgentStepResult command) { return transaction(() -> delegate.commitAgentStepResult(command)); }
    @Override public CommitReceipt appendRunInput(AppendRunInput command) { return transaction(() -> delegate.appendRunInput(command)); }
    @Override public CommitReceipt commitTurnResult(WriteResult command) { return transaction(() -> delegate.commitTurnResult(command)); }
    @Override public CommitReceipt reviseMessage(ReviseMessage command) { return transaction(() -> delegate.reviseMessage(command)); }
    @Override public CommitReceipt selectBranch(SelectBranch command) { return transaction(() -> delegate.selectBranch(command)); }
    @Override public CommitReceipt forkConversation(ForkConversation command) { return transaction(() -> delegate.forkConversation(command)); }
    @Override public CommitReceipt updateTitle(UpdateTitle command) { return transaction(() -> delegate.updateTitle(command)); }
    @Override public CommitReceipt recordRunState(RunState command) { return transaction(() -> delegate.recordRunState(command)); }
    @Override public CommitReceipt deleteConversation(DeleteConversation command) { return transaction(() -> delegate.deleteConversation(command)); }
    @Override public CommitReceipt saveExecutionRecord(String scope, String operation, ExecutionSearchRecord record) {
        return transaction(() -> delegate.saveExecutionRecord(scope, operation, record));
    }
    @Override public Optional<ExecutionSearchRecord> findExecutionRecord(String scope, String id, String record) {
        return transaction(() -> delegate.findExecutionRecord(scope, id, record));
    }
    @Override public Optional<CommitReceipt> findOperation(String scope, String operation) {
        return transaction(() -> delegate.findOperation(scope, operation));
    }
    @Override public ChangePage readCommittedChanges(String scope, long cursor, int limit) {
        return transaction(() -> delegate.readCommittedChanges(scope, cursor, limit));
    }
}
