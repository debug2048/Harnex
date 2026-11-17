package com.harnex.tool.builtin;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.core.AgentRuntimeBudget;
import com.harnex.subagent.SubAgentExecutor;
import com.harnex.subagent.SubAgentExecutor.DelegateRequest;
import com.harnex.tool.Tool;
import com.harnex.tool.ToolCapability;
import com.harnex.tool.ToolContext;
import com.harnex.tool.ToolDefinition;
import com.harnex.tool.ToolResult;
import com.harnex.toolset.ToolsetRegistry;

/**
 * Tool that delegates a sub-task to a headless sub-agent (Hermes / Claude Code style). The sub-agent runs a full
 * AgentLoop with its own thread and toolset.
 */
@Component
@ProjectRuntime
public class DelegateTool implements Tool {

    private final SubAgentExecutor executor;
    private final AgentConfig cfg;
    /**
     * Lazy proxy: ToolRegistry collects all {@link Tool} beans (including this one), and ToolsetRegistry depends on
     * ToolRegistry. Without {@code @Lazy} the constructor would complete a 3-bean cycle (ToolRegistry → DelegateTool →
     * ToolsetRegistry → ToolRegistry) that Spring rejects with "Requested bean is currently in creation". The proxy is
     * only dereferenced inside {@link #getDefinition()}, by which time the cycle is fully wired.
     */
    private final ToolsetRegistry toolsetRegistry;

    public DelegateTool(SubAgentExecutor executor, AgentConfig cfg, @Lazy ToolsetRegistry toolsetRegistry) {
        this.executor = executor;
        this.cfg = cfg;
        this.toolsetRegistry = toolsetRegistry;
    }

    @Override
    public String getName() {
        return "delegate";
    }

    @Override
    public String getDescription() {
        int limit = Math.max(1, cfg.getSubagent().getMaxPerThread());
        long timeoutMs = cfg.getSubagent().getTimeoutMs();
        long minutes = timeoutMs > 0 ? timeoutMs / 60_000L + (timeoutMs % 60_000L == 0 ? 0 : 1) : 0L;
        String deadline = timeoutMs > 0 ? minutes + (minutes == 1 ? " minute" : " minutes")
            : "the configured runtime budget";
        return "Delegate one bounded sub-task to a sub-agent with its own tool loop and an explicit "
            + "definition of done. Useful for parallelising complex research or coding tasks. At most " + limit
            + " delegates may be issued per turn; submit larger plans in waves after results return. Keep each task "
            + "small enough to finish within the hard execution limit of " + deadline
            + ". Queue wait has a separate limit. Runtime checkpoints allow continued progress; parent/Goal budgets "
            + "still apply. Return a partial handoff when the remaining budget cannot cover the task. "
            + "Handle child questions and plan proposals as the parent: first use your own context and authorized alternatives, "
            + "then ask the user only if necessary. Do not blindly repeat a blocked subtask or redo its completed side effects.";
    }

    @Override
    public Set<ToolCapability> capabilities() {
        // Sibling delegations are intentionally parallel. SubAgentExecutor owns the atomic family budget and
        // per-parent fan-out limit; ParallelToolExecutor routes the blocking parent-side waits to a coordination pool
        // so child loops can continue using the ordinary tool pool without a starvation deadlock.
        return EnumSet.of(ToolCapability.MUTATES_THREAD_STATE,
            ToolCapability.CONCURRENCY_SAFE,
            ToolCapability.ORCHESTRATES_AGENT);
    }

    @Override
    public int maxParallel() {
        // The real limit is contextual (SubAgentExecutor.maxPerThread), not process-global per tool name.
        return Integer.MAX_VALUE;
    }

    @Override
    public int timeoutMillis() {
        // The outer watchdog covers queue + execution + cancellation drain. The executor owns the phase deadlines.
        long ms = cfg.getSubagent().getTimeoutMs();
        if (ms <= 0) {
            // Only the local execution cap is disabled. ParallelToolExecutor still clamps its outer guard to 24h.
            return Integer.MAX_VALUE;
        }
        long withQueue = AgentRuntimeBudget.saturatedAdd(ms,
            Math.max(1, cfg.getSubagent().getQueueTimeoutMs()));
        return (int)Math.min(Integer.MAX_VALUE,
            AgentRuntimeBudget.saturatedAdd(withQueue, 5_000L));
    }

    @Override
    public ToolDefinition getDefinition() {
        // Pull the actual preset names from the live registry so the schema enum advertises
        // exactly what `ToolsetRegistry.resolve` will accept. Hardcoding `coding` here used to
        // silently misroute the model: it would dutifully echo `["coding"]`, the registry
        // would log "unknown preset" + fail-closed to `read`, and the sub-agent would lose
        // bash / file_write without any visible error in the parent loop. Sourcing from
        // listPresets() keeps schema and runtime in lockstep across operator-defined presets.
        List<String> presetNames = new ArrayList<>(toolsetRegistry.listPresets());
        List<String> defaultSet = cfg.getToolsets() != null && cfg.getToolsets().getDefaultSet() != null
            && !cfg.getToolsets().getDefaultSet().isEmpty() ? cfg.getToolsets().getDefaultSet() : List.of("act");
        String desc = "Toolsets for the sub-agent. Default: " + defaultSet + ". Valid values: " + presetNames + ".";
        Map<String, Object> toolsetsItems = new LinkedHashMap<>();
        toolsetsItems.put("type", "string");
        if (!presetNames.isEmpty()) {
            // enum lets the model's schema-aware decoder reject typos client-side instead of
            // discovering them only after the sub-agent silently degrades.
            toolsetsItems.put("enum", presetNames);
        }
        return ToolDefinition.builder().name(getName()).description(getDescription())
            .parameters(Map.of("type", "object", "properties",
                Map.of("goal",
                    Map.of("type", "string", "description",
                        "Clear description of what the sub-agent should accomplish"),
                    "context",
                    Map.of("type", "string", "description", "Relevant context/background the sub-agent needs"),
                    "completionCriteria",
                    Map.of("type", "string", "description",
                        "Required observable definition of done. State exactly what evidence or deliverable lets the "
                            + "sub-agent stop."),
                    "toolsets", Map.of("type", "array", "items", toolsetsItems, "description", desc),
                    "maxIterations",
                    Map.of("type", "integer", "description",
                        "Optional lower iteration cap for this sub-agent. Omit to use "
                            + "the effective harnex.subagent.max-iterations ceiling; "
                            + "this cannot raise the operator ceiling.")),
                "required", new String[] {"goal", "completionCriteria"}))
            .build();
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        DelegateRequest req = new DelegateRequest();
        req.setGoal(String.valueOf(input.getOrDefault("goal", "")));
        req.setContext(input.containsKey("context") ? String.valueOf(input.get("context")) : null);
        req.setCompletionCriteria(
            input.containsKey("completionCriteria") ? String.valueOf(input.get("completionCriteria")) : null);
        if (input.containsKey("toolsets") && input.get("toolsets") instanceof List<?> ts) {
            if (ts.stream().map(String::valueOf).anyMatch(toolsetRegistry::isClientPreset)) {
                return ToolResult.failure("Editor connection toolsets are private to the root client; choose a regular sub-agent toolset.");
            }
            req.setToolsets(ts.stream().map(String::valueOf).toList());
        }
        if (input.get("maxIterations") instanceof Number n) {
            req.setMaxIterations(n.intValue());
        }
        if (ctx.getThread() != null) {
            var parent = ctx.getThread();
            req.setDepth(parent.getSubagentDepth());
            req.setParentThreadId(parent.getId());
            // A delegate is part of the current conversation's agent family. Inherit the complete model identity so
            // a child never becomes a separately routed/model-selected agent.
            req.setModel(parent.getModel());
            req.setProvider(parent.getProvider());
            req.setReasoningEffort(parent.getReasoningEffort());
        }
        // Hand the parent's shared token / ask budget to the sub-agent so a cost-capped deployment
        // observes the cost of every descendant. Iterations are NOT shared: each agent bounds its
        // own loop locally (harnex.maxIterations by default, an explicit lower subagent ceiling,
        // or the lower per-sub-agent override above). Null is fine:
        // legacy ToolContext callers (older tests) leave it unset and the sub-agent falls back to
        // its own root-scope budget.
        req.setParentBudget(ctx.getBudget());
        req.setParentContext(ctx.getAgentContext());
        req.setRunId(ctx.getRunId());
        req.setDelegateCallId(ctx.getToolCallId());
        req.setRootThreadId(ctx.getRootThreadId());
        req.setParentSnapshotContext(ctx.getSnapshotContext());
        // Forward the child's structured execution trail through the owning run's
        // event pipeline. SubAgentExecutor wraps these as subagent_* events so child
        // text is never confused with the parent assistant stream.
        req.setEventSink(ctx.getEmit());
        req.setOptions(ctx.getOptions());
        // Propagate parent permission mode so sub-agent inherits the same approval posture.
        if (ctx.getThread() != null && ctx.getThread().getState() != null) {
            req.setParentPermissionMode(ctx.getThread().getState().getPermissionMode());
        }

        // Parent-reserve: refuse to delegate when the parent agent has no further iteration to
        // consume the sub-agent's result. The delegate call spends the current iteration; the
        // result is appended as a tool message that only the NEXT LLM turn can read and summarise.
        // With < 2 iterations left (current + at least one more), that follow-up turn never runs and
        // the user sees "sub-agent finished but the assistant never reported back". iterationsRemaining
        // defaults to MAX_VALUE for non-loop callers, so this never trips outside a real agent loop.
        if (ctx.getIterationsRemaining() < 2) {
            return ToolResult.builder().error(true)
                .content("Refusing to delegate: the parent agent has no remaining iteration to consume the "
                    + "sub-agent's result. Summarise now with what you have, or raise harnex.max-iterations "
                    + "for larger tasks.")
                .build();
        }

        try {
            SubAgentExecutor.SubAgentResult result = executor.delegate(req);
            // Honour the sub-agent's own error flag — timeout / interrupt / depth-cap / queue-full
            // / runner exception all surface as {@code errored=true}. Without checking this, the
            // model treated "[sub-agent timed out]" as a successful delegation result and made
            // downstream decisions based on the partial / aborted output.
            String body = "Sub-agent " + (result.isErrored() ? "did not complete cleanly" : "completed") + " (thread: "
                + result.getThreadId() + ")\n\n" + result.getOutput();
            return ToolResult.builder().error(result.isErrored()).content(body).build();
        } catch (Exception e) {
            return ToolResult.builder().error(true).content("Sub-agent failed: " + e.getMessage()).build();
        }
    }
}
