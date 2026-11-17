package com.harnex.conversation;

import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import com.harnex.config.ProjectRuntime;
import com.harnex.execution.ScopeIdentity;
import com.harnex.run.IdempotencyReceiptStore;
import com.harnex.tool.ToolResult;

import lombok.extern.slf4j.Slf4j;

/** Projects already-redacted tool output without admitting tool protocol into conversational history. */
@Component @ProjectRuntime
@Slf4j
public final class AgentExecutionSearch {
    private final ConversationRepository archive;
    private final ScopeIdentity scope;
    public AgentExecutionSearch(ConversationRepository archive,ScopeIdentity scope) { this.archive = archive; this.scope = scope; }
    public HistoryReference record(String conversationId,String runId,String stepId,int iteration,ToolResult result,String safeText) {
        if (result.getCallId() == null || safeText == null || safeText.isBlank()) return null;
        String id = "tool_" + IdempotencyReceiptStore.requestHash(Map.of("run",runId,"step",stepId == null ? "" : stepId,"iteration",iteration,"call",result.getCallId())).substring(0,32);
        try {
            if (archive.loadConversation(scope.scopeId(),conversationId).isEmpty()) return null;
            String text = safeText.substring(0,Math.min(safeText.length(),1_000_000));
            if (!text.isEmpty() && Character.isHighSurrogate(text.charAt(text.length()-1))) text = text.substring(0,text.length()-1);
            String title = result.getToolName() == null ? "tool" : result.getToolName();
            String sourceMessageId = stepId == null ? null : ConversationRepository.agentStepMessageId(runId, stepId);
            var previous = archive.findExecutionRecord(scope.scopeId(),conversationId,id).orElse(null);
            if (previous != null && text.equals(previous.text()) && Objects.equals(sourceMessageId, previous.sourceMessageId())) {
                return new HistoryReference(conversationId, null, id, previous.sourceRevision());
            }
            long revision = previous == null ? 1 : previous.sourceRevision()+1;
            archive.saveExecutionRecord(scope.scopeId(),id + "_" + revision,new ExecutionSearchRecord(id,conversationId,runId,result.getCallId(),revision,title,text,false,sourceMessageId));
            return new HistoryReference(conversationId, null, id, revision);
        } catch (RuntimeException unavailable) {
            log.warn("Tool search projection unavailable for run={} call={}",runId,result.getCallId());
            return null;
        }
    }
}
