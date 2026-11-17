package com.harnex.middleware;

import org.springframework.stereotype.Component;

import com.harnex.config.ProjectRuntime;
import com.harnex.core.AgentCtx;
import com.harnex.goal.GoalPromptContext;
import com.harnex.skill.SkillDirectiveHelper;

/**
 * Re-injects the agent's core rules every N LLM turns. Long conversations + history compression cause the model to
 * drift away from the original system prompt; periodic reinforcement (Claude Code's "system reminder" pattern)
 * measurably restores compliance with rules like "always use tools" and "ask before destructive ops".
 *
 * <p>
 * Implementation: queue a request-scoped instruction on {@link AgentCtx}. {@code AgentLoop} attaches it to the latest
 * input/result for one Provider call, keeping the stable system prefix intact. It is never appended to durable history and therefore can
 * never masquerade as the latest human request after compression.
 * </p>
 */
@Component
@ProjectRuntime
public class SystemReminderMiddleware implements Middleware {

    /** Inject reminder every N iterations. 0/negative disables. */
    private static final int EVERY_N_TURNS = 5;

    private static final String REMINDER_TEXT = "Periodic operating-rules checkpoint:\n"
        + "  • Always use tools to gather facts; never invent file contents, URLs or APIs.\n"
        + "  • Prefer read-only tools first (file_read, glob, grep, web_fetch) before mutating.\n"
        + "  • For destructive shell ops, expect approval prompts; explain *why* before running.\n"
        + "  • Stay in the user's language and continue the CURRENT human request identified by the continuity block.";

    @Override
    public int order() {
        return MiddlewareOrder.SYSTEM_REMINDER;
    }

    @Override
    public void afterTools(AgentCtx ctx) {
        if (EVERY_N_TURNS <= 0) {
            return;
        }

        // Skip for /skill explicit invocations (focused execution with own instructions),
        // delegate sub-agents (short-lived with own system prompt),
        // and autonomous goal loops (continuation prompt re-injects rules every turn)
        if (SkillDirectiveHelper.isSkillDirective(ctx.getThread()) 
            || ctx.getThread().getSubagentDepth() > 0
            || GoalPromptContext.active(ctx.getThread().getMessages())) {
            return;
        }

        // afterTools runs at the end of an iteration; iteration is 0-indexed.
        // Trigger at the end of iter N-1, 2N-1, ... so the reminder lands immediately
        // before the next LLM call.
        int iter = ctx.getIteration() + 1;
        if (iter <= 0 || iter % EVERY_N_TURNS != 0) {
            return;
        }

        ctx.queueRequestReminder("system-rules-cadence:" + iter, REMINDER_TEXT);
    }

}
