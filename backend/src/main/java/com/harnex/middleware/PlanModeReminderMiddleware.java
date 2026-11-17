package com.harnex.middleware;

import org.springframework.stereotype.Component;

import com.harnex.config.ProjectRuntime;
import com.harnex.core.AgentCtx;
import com.harnex.core.PlanModeService;

/**
 * Injects the dynamic {@code <plan_mode_state>} reminder whenever the thread is in plan-mode, so the model KNOWS it is
 * in plan-mode (the executor blocks write/exec tools either way, but without this the model didn't understand why and
 * couldn't drive the explore → submit_plan workflow).
 *
 * <p>
 * Done per-turn as a request-scoped system instruction (the base system prompt is built once by {@code PromptBuilder}
 * and so cannot track a flag that toggles mid-conversation via {@code /plan}). It never enters durable history and is
 * only present while the flag is active. Mirrors {@link SystemReminderMiddleware}'s request-only approach.
 * </p>
 */
@Component
@ProjectRuntime
public class PlanModeReminderMiddleware implements Middleware {

    private static final String TAG = "[plan-mode]";
    private static final String TEXT = TAG + " You are in PLAN MODE. Only read-only / planning tools run; "
        + "write and exec tools are blocked. Explore the codebase read-only, then call submit_plan(plan_markdown) "
        + "to present your plan for the user's approval before any changes. On approval the plan's steps become your "
        + "todo list and you execute; on rejection you receive feedback and revise. "
        + "If planning should be abandoned, ask the user to run /plan cancel.";

    private final PlanModeService planMode;

    public PlanModeReminderMiddleware(PlanModeService planMode) {
        this.planMode = planMode;
    }

    @Override
    public int order() {
        // Right after the generic system-reminder so both land in the same pre-LLM tail region.
        return MiddlewareOrder.SYSTEM_REMINDER + 10;
    }

    @Override
    public void before(AgentCtx ctx) {
        inject(ctx, "initial");
    }

    @Override
    public void afterTools(AgentCtx ctx) {
        inject(ctx, "after-tools:" + ctx.getIteration());
    }

    private void inject(AgentCtx ctx, String requestKey) {
        String tid = ctx.getThread() == null ? null : ctx.getThread().getId();
        if (!planMode.isActive(tid)) {
            return;
        }
        // An active Plan flag is an explicit review gate. Self-managed Goal planning submits a plan directly.
        ctx.queueRequestReminder("plan-mode:" + requestKey, TEXT);
    }
}
