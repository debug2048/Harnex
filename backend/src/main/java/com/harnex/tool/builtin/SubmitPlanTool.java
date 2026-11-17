package com.harnex.tool.builtin;

import com.harnex.core.ConversationContinuity;
import com.harnex.todo.TodoStore;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.core.InteractionBroker;
import com.harnex.core.PlanModeService;
import com.harnex.execution.PlanReviewPolicy;
import com.harnex.core.PlanReviewService;
import com.harnex.core.PlanReviewService.PlanDecision;
import com.harnex.model.StreamEvent;
import com.harnex.tool.*;

import lombok.extern.slf4j.Slf4j;

/**
 * Persist a plan before accepting it. Root tasks use their explicit review policy; a required review holds the
 * read-only gate until the user approves. Subtask planning is internal, with review proposals returned to the parent.
 * Missing reply capability or timeout never satisfies a required review. Plan acceptance does not expand authority.
 */
@Slf4j
@Component
@ProjectRuntime
public class SubmitPlanTool implements Tool {
    private final TodoStore todoStore;

    /** Markdown list item / numbered step → a seed todo title. */
    private static final Pattern STEP = Pattern.compile("^\\s*(?:[-*+]|\\d+[.)])\\s+(.*\\S)\\s*$");
    private static final int MAX_SEED_TODOS = 30;

    private final PlanModeService planMode;
    private final PlanReviewService planReview;
    private final AgentConfig cfg;

    public SubmitPlanTool(PlanModeService planMode, PlanReviewService planReview, AgentConfig cfg, TodoStore todoStore) {
        this.todoStore = todoStore;
        this.planMode = planMode;
        this.planReview = planReview;
        this.cfg = cfg;
    }

    @Override
    public String getName() {
        return "submit_plan";
    }

    @Override
    public String getDescription() {
        return "Record a plan in markdown. Root tasks follow the configured plan-review requirement; an active "
            + "manual Plan gate still needs user approval. Sub-agents plan internally and return proposals needing "
            + "review to their parent instead of opening a separate user review. Set require_review=true for an "
            + "explicit review requirement. Plan acceptance never expands the requested task or tool permissions.";
    }

    @Override
    public Set<ToolCapability> capabilities() {
        return EnumSet.of(ToolCapability.MUTATES_THREAD_STATE, ToolCapability.PLAN_MODE_SAFE,
            ToolCapability.APPROVAL_FREE);
    }

    @Override
    public int timeoutMillis() {
        // The await below can block for the whole review window; widen the executor watchdog to match so it isn't
        // killed at the static 30s default. Clamp to a sane ceiling.
        long t = cfg.getPlan() == null ? 1_800_000L : cfg.getPlan().getReviewTimeoutMs();
        return (int)Math.min(Integer.MAX_VALUE, Math.max(30_000L, t + 30_000L));
    }

    @Override
    public boolean awaitsUserInput() {
        return true;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder().name(getName()).description(getDescription())
            .parameters(Map.of("type", "object", "properties",
                Map.of("plan_markdown",
                    Map.of("type", "string", "description",
                        "The complete plan as markdown: goal, the concrete steps, and any risks. Steps as a bullet or "
                            + "numbered list become the executable todo checklist on approval."),
                    "require_review", Map.of("type", "boolean", "description",
                        "Set true when the user explicitly requires human review before execution. This can only tighten the configured review requirement.")),
                "required", new String[] {"plan_markdown"}))
            .build();
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        String plan = String.valueOf(input.getOrDefault("plan_markdown", "")).trim();
        if (plan.isEmpty()) {
            return ToolResult.failure("'plan_markdown' must be a non-empty plan");
        }
        String tid = ctx.getThread() == null ? null : ctx.getThread().getId();
        int ownerTurn = ConversationContinuity.humanTurnCount(ctx.getThread());
        boolean child = ctx.getThread() != null && ctx.getThread().getSubagentDepth() > 0;
        boolean reviewRequired = Boolean.TRUE.equals(input.get("require_review")) || planMode.isActive(tid)
            || !child && ctx.getOptions().planReview() == PlanReviewPolicy.REQUIRED;
        try {
            if (reviewRequired) planMode.enable(tid);
            persistPlan(tid, plan);
        } catch (IOException | UncheckedIOException failure) {
            log.warn("submit_plan persist for {} failed: {}", tid, failure.toString());
            return ToolResult.failure("Cannot save the plan or its review requirement. The plan was not accepted; "
                + "resolve the storage failure before executing its steps.");
        }

        String callId = "plan-" + UUID.randomUUID();

        if (ctx.getThread() != null && ctx.getThread().getSubagentDepth() > 0) {
            if (Boolean.TRUE.equals(input.get("require_review")) || planMode.isActive(tid)) {
                return ctx.requireInput("Plan requiring parent review:\n" + plan
                    + "\nReturn this proposal to the parent. Do not execute it or request a separate user review.");
            }
            approve(ctx, plan, null, ownerTurn);
            return ToolResult.ok("Subtask plan recorded. Continue only within the delegated scope and existing permissions. "
                + "If this is a planning-only subtask, return the plan to the parent without executing its steps.");
        }

        if (ctx.getOptions().planReview() == PlanReviewPolicy.AUTOMATIC
            && !Boolean.TRUE.equals(input.get("require_review")) && !planMode.isActive(tid)) {
            return approve(ctx, plan, "(self-managed plan; existing tool permissions still apply)", ownerTurn);
        }
        if (!InteractionBroker.available(ctx)) {
            planMode.enable(tid);
            return ctx.requireInput("Plan review required. The plan is saved; approve it before execution.");
        }
        String replyThread = InteractionBroker.threadId(ctx);
        planMode.enable(tid);
        // Pre-register the waiter BEFORE emitting so a synchronous CLI consumer that records the decision from inside
        // the emit callback is not lost (mirrors ApprovalService.registerPending).
        long timeout = cfg.getPlan() == null ? 1_800_000L : cfg.getPlan().getReviewTimeoutMs();
        long deadline = System.currentTimeMillis() + InteractionBroker.timeout(ctx, timeout);
        planReview.registerPending(replyThread, callId, Map.of("plan", plan, "expiresAt", deadline));
        if (ctx.getEmit() != null) ctx.getEmit().accept(
            StreamEvent.of("plan_review", Map.of("callId", callId, "threadId", replyThread == null ? "" : replyThread, "plan", plan, "expiresAt", deadline)));

        PlanDecision decision = planReview.await(replyThread, callId, Math.max(1L, deadline - System.currentTimeMillis()),
            () -> InteractionBroker.stopped(ctx));
        if (decision == null) {
            // Interactive but no response within the window (or plan-mode was cancelled via /plan cancel).
            if (!planMode.isActive(tid)) {
                return ToolResult.ok("Plan-mode has been cancelled by the user. Do not execute this plan; treat it "
                    + "as abandoned and continue in normal mode.");
            }
            // Still in plan-mode: genuine timeout — do NOT auto-execute (writes could be destructive); stay
            // in plan-mode and tell the model to wait.
            return ctx.requireInput("Plan review required. The plan is saved and remains in read-only mode.");
        }
        if (decision.approved()) {
            return approve(ctx, plan, null, ownerTurn);
        }
        String feedback = decision.feedback() == null || decision.feedback().isBlank() ? "(no feedback provided)"
            : decision.feedback();
        return ToolResult.ok("Plan REJECTED. You are still in plan-mode. User feedback:\n" + feedback
            + "\n\nRevise the plan accordingly and call submit_plan again.");
    }

    private ToolResult approve(ToolContext ctx, String plan, String suffix, int ownerTurn) {
        planMode.disable(ctx.getThread().getId());
        List<String> steps = extractSteps(plan);
        todoStore.seed(ctx.getThread(), ctx.getRunId(), steps, ownerTurn);
        String msg =
            "Plan APPROVED. Plan-mode disabled" + (steps.isEmpty() ? "" : ", " + steps.size() + " todos seeded")
                + ". Execute the plan now; keep todo_write up to date as you go.";
        return ToolResult.ok(suffix == null ? msg : msg + " " + suffix);
    }

    /** Pull bullet / numbered list items out of the plan markdown as todo titles (capped). */
    static List<String> extractSteps(String plan) {
        List<String> steps = new ArrayList<>();
        for (String line : plan.split("\n")) {
            Matcher m = STEP.matcher(line);
            if (m.matches()) {
                // Strip a leading markdown bold/heading marker noise; keep the text.
                String title = m.group(1).replaceAll("\\*\\*", "").strip();
                if (!title.isEmpty()) {
                    steps.add(title);
                }
                if (steps.size() >= MAX_SEED_TODOS) {
                    break;
                }
            }
        }
        return steps;
    }

    private void persistPlan(String tid, String plan) throws IOException {
        if (tid == null) {
            return;
        }
        Path dir = Paths.get(cfg.getDataDir(), "agent/plans", tid);
        Files.createDirectories(dir);
        Path temporary = Files.createTempFile(dir, "plan-", ".tmp");
        try {
            Files.writeString(temporary, plan, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, dir.resolve("plan.md"), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, dir.resolve("plan.md"), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
