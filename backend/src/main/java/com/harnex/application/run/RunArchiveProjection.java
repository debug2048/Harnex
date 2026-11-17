package com.harnex.application.run;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.harnex.conversation.*;
import com.harnex.core.AgentConversationSink;
import com.harnex.execution.ScopeIdentity;
import com.harnex.image.ImageResultContent;
import com.harnex.run.*;

/** Lifecycle-to-history adapter. Projection failures cannot cause a provider retry or release a sleeping Goal. */
@Component
public final class RunArchiveProjection implements RunArchiveSink {
    private final ConversationRepository archive;
    private final ScopeIdentity scope;
    @Autowired(required = false)
    private ImageResultContent images;
    public RunArchiveProjection(ConversationRepository archive, ScopeIdentity scope) { this.archive = archive; this.scope = scope; }
    @Override public CommitReceipt beforeTerminal(RunHandle run, RunStatus status, String detail) {
        // Ordinary executions explicitly commit their result through RunRequestService or their Agent adapter.
        if (run.getSubmission() == null || run.getGoalId() == null && run.getOrigin().kind() == RunOrigin.Kind.CLIENT) return null;
        if (archive.loadConversation(scope.scopeId(), run.getThreadId()).isEmpty() && status != RunStatus.COMPLETED) return null;
        var ref = run.getSubmission();
        if (archive.findOperation(scope.scopeId(), ref.acceptOperationId()).isEmpty()) {
            if (status == RunStatus.COMPLETED) throw new ConversationException("result_not_archived", "Goal input was not archived");
            return null;
        }
        var replay = archive.findOperation(scope.scopeId(), run.getRunId() + "_result");
        if (replay.isPresent()) return replay.get();
        var turn = archive.findTurn(scope.scopeId(), run.getThreadId(), ref.turnId()).orElseThrow();
        var answer = archive.findMessage(scope.scopeId(), run.getThreadId(), turn.activeAttemptId()).orElseThrow();
        MessageRecord.Status answerStatus = switch (status) { case COMPLETED -> MessageRecord.Status.COMPLETED;
            case CANCELLED -> MessageRecord.Status.CANCELLED; case INCOMPLETE, INTERRUPTED -> MessageRecord.Status.INCOMPLETE;
            default -> MessageRecord.Status.FAILED; };
        // Public step messages already hold the Goal's output. The root answer records its final disposition once.
        List<ContentPart> content = detail == null || detail.isBlank() ? answer.contentParts() : List.of(ContentPart.text(detail));
        if (run.getGoalId() == null && run.getLastStepResult() != null) content = AgentConversationSink.resultContent(run.getLastStepResult());
        if (images != null) content = images.append(run.getRunId(), null, content);
        return archive.commitTurnResult(new ConversationRepository.WriteResult(scope.scopeId(), run.getThreadId(),
            run.getRunId() + "_result", ref.turnId(), run.getRunId(), content, answerStatus));
    }
    @Override public void stateChanged(RunHandle run) {
        if (run.getSubmission() == null || archive.findOperation(scope.scopeId(), run.getSubmission().acceptOperationId()).isEmpty()) return;
        var meta = archive.loadConversation(scope.scopeId(), run.getThreadId()).orElse(null);
        if (meta == null || !run.getRunId().equals(meta.latestRunId()) || run.getStatus().name().equals(meta.latestRunStatus())) return;
        archive.recordRunState(new ConversationRepository.RunState(scope.scopeId(), run.getThreadId(),
            run.getRunId() + "_state_" + run.getRevision(), run.getRunId(), run.getStatus().name()));
    }
}
