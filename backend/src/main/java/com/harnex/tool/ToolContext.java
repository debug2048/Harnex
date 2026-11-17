package com.harnex.tool;

import java.util.function.Consumer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.harnex.core.AgentCtx;
import com.harnex.core.AgentExecutionOptions;
import com.harnex.core.InteractionBroker;
import com.harnex.core.PermissionContext;
import com.harnex.core.StepLimits;
import com.harnex.core.SubAgentBudget;
import com.harnex.model.ConversationThread;
import com.harnex.model.StreamEvent;
import com.harnex.snapshot.SnapshotService;

import lombok.Data;

@Data
public class ToolContext {
    private ConversationThread thread;
    /** Root directory for the workspace — the project root (physical path). */
    private String workspaceDir;
    /** SSE emitter for sending events to the client (e.g. approval_required). */
    private Consumer<StreamEvent> emit;
    /**
     * Shared budget tracker propagated from the owning {@link com.harnex.core.AgentCtx}. The {@code delegate} tool
     * reads this to hand the SAME budget down to sub-agents so the token / family-run caps apply across the entire
     * family of agents spawned by one user turn. Nullable for legacy / test callers that construct a context outside
     * the agent loop.
     */
    private SubAgentBudget budget;
    /** Owning execution, used by delegations to inherit time limits and observe cancellation while queued. */
    private AgentCtx agentContext;
    /**
     * Execution policy for the owning run (docs/03 §3.14). Never {@code null} once {@link com.harnex.core.AgentLoop}
     * has built the context; defaults to interactive chat for legacy/test callers that construct a context directly.
     */
    private AgentExecutionOptions options = new AgentExecutionOptions(
        PermissionContext.UNRESTRICTED, new StepLimits(64, 2), null);
    /**
     * Iterations remaining in the OWNING agent loop, including the current one. Set by {@link com.harnex.core.AgentLoop}
     * before each tool batch so a tool can tell whether the parent has a further turn. The {@code delegate} tool uses
     * this to refuse delegating on the parent's final iteration — otherwise the sub-agent's result lands as a tool
     * message that no subsequent LLM turn ever consumes. Defaults to {@link Integer#MAX_VALUE} (= "unknown / unlimited",
     * so legacy callers never trip the guard).
     */
    private int iterationsRemaining = Integer.MAX_VALUE;
    private String runId;
    private String rootThreadId;
    private String agentId = "root";
    private String subagentThreadId;
    /** Provider-issued id of the tool call currently using this context. */
    private String toolCallId;
    /** Exact local tool names offered for this model call; null only for legacy/non-agent callers. */
    private Set<String> advertisedTools;
    private SnapshotService.SnapshotCaptureContext snapshotContext;
    /** Exact call ids admitted through the YELLOW-zone approval gate. Consumed by forToolCall(). */
    private final Set<String> outsideWriteApprovals = ConcurrentHashMap.newKeySet();
    private boolean outsideWriteApproved;

    public ToolContext(ConversationThread thread, String workspaceDir) {
        this(thread, workspaceDir, null, null);
    }

    public ToolContext(ConversationThread thread, String workspaceDir, Consumer<StreamEvent> emit) {
        this(thread, workspaceDir, emit, null);
    }

    public ToolContext(ConversationThread thread, String workspaceDir, Consumer<StreamEvent> emit,
        SubAgentBudget budget) {
        this.thread = thread;
        this.workspaceDir = workspaceDir;
        this.emit = emit;
        this.budget = budget;
    }

    /**
     * Create an invocation-local view of this context. Tool calls can execute concurrently, so
     * call identity must never be written onto the shared parent context.
     */
    public ToolContext forToolCall(String callId) {
        ToolContext copy = new ToolContext(thread, workspaceDir, emit, budget);
        copy.setAgentContext(agentContext);
        copy.setOptions(options);
        copy.setIterationsRemaining(iterationsRemaining);
        copy.setRunId(runId);
        copy.setRootThreadId(rootThreadId);
        copy.setAgentId(agentId);
        copy.setSubagentThreadId(subagentThreadId);
        copy.setToolCallId(callId);
        copy.setAdvertisedTools(advertisedTools);
        copy.setSnapshotContext(snapshotContext);
        copy.setOutsideWriteApproved(callId != null && outsideWriteApprovals.remove(callId));
        return copy;
    }

    public void approveOutsideWrite(String callId) {
        if (callId != null && !callId.isBlank()) outsideWriteApprovals.add(callId);
    }

    public ToolResult requireInput(String detail) {
        if (agentContext != null && !InteractionBroker.stopped(this)) agentContext.needsInput(detail);
        return ToolResult.builder().error(true).requiresInput(true).content(detail).build();
    }

}
