package com.harnex.middleware;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.core.AgentCtx;
import com.harnex.goal.GoalPromptContext;

import lombok.extern.slf4j.Slf4j;

/**
 * Periodic Core-Memory nudge.
 *
 * <p>
 * Long sessions tend to forget that {@code MEMORY.md} / {@code USER.md} / {@code LESSONS.md} exist (the system-prompt
 * slot is small and the model's attention drifts after dozens of tool results scroll past it). This middleware injects
 * a one-line request-scoped memory instruction every {@code EVERY_N_TURNS} iterations
 * <strong>OR</strong> when {@code MEMORY.md} has been edited in the last 24 h — whichever fires first — to remind the
 * agent to check or refresh stored facts before answering.
 *
 * <p>
 * Only the memory file's timestamp is read here; its contents are loaded by {@link com.harnex.core.PromptBuilder}.
 * Failure to stat is silently ignored so a missing data dir cannot break the loop.
 */
@Slf4j
@Component
@ProjectRuntime
public class MemoryNudgeMiddleware implements Middleware {

    /** Inject reminder every N iterations. 0/negative disables the count-based trigger. */
    private static final int EVERY_N_TURNS = 10;
    /** Recent-edit window: if {@code MEMORY.md} was modified within this many hours, nudge sooner. */
    private static final long RECENT_EDIT_HOURS = 24;
    /** Slot in the chain: right after SystemReminder so both run with fresh thread-data. */
    private static final int ORDER = MiddlewareOrder.SYSTEM_REMINDER + 5;
    private static final String NUDGE_TEXT =
        "For this conversation's constraints, decisions and corrections, use session_fact when provided, with exact "
            + "USER source quotes and the correct branch/scope. Use memory_remember only for stable preferences or "
            + "project knowledge intended for future sessions; do not copy task-local constraints or automatically "
            + "overwrite one store from the other. The stable system context may contain older USER.md / MEMORY.md / "
            + "LESSONS.md excerpts; use memory_list when provided if their current contents matter.";

    private final Path memoryFile;

    public MemoryNudgeMiddleware(AgentConfig cfg) {
        Path dir = Paths.get(cfg.getDataDir(), "memory");
        this.memoryFile = dir.resolve("MEMORY.md");
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public void afterTools(AgentCtx ctx) {
        // Skip goal mode: continuation prompt provides task context every turn,
        // memory nudges would compete for model attention on the main objective.
        if (GoalPromptContext.active(ctx.getThread().getMessages())) {
            return;
        }

        int iter = ctx.getIteration() + 1;
        if (iter <= 0) {
            return;
        }
        boolean turnsHit = EVERY_N_TURNS > 0 && iter % EVERY_N_TURNS == 0;
        boolean recentEdit = isRecentlyEdited();
        if (!turnsHit && !recentEdit) {
            return;
        }

        String key = turnsHit ? "memory-cadence:" + iter : "memory-recent";
        if (ctx.queueRequestReminder(key, NUDGE_TEXT)) {
            log.debug("memory nudge queued at iter={} (turnsHit={} recentEdit={})", iter, turnsHit, recentEdit);
        }
    }

    private boolean isRecentlyEdited() {
        try {
            if (!Files.exists(memoryFile)) {
                return false;
            }
            Instant mtime = Files.getLastModifiedTime(memoryFile).toInstant();
            return mtime.isAfter(Instant.now().minus(RECENT_EDIT_HOURS, ChronoUnit.HOURS));
        } catch (IOException ignored) {
            return false;
        }
    }

}
