package com.harnex.core;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.io.UncheckedIOException;
import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.util.Ids;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.stereotype.Component;

/**
 * Per-thread plan-mode flag. While a thread is in plan mode, only read-only tools are permitted; any non-read-only tool
 * call is short-circuited with a hint to the model to finish reading/planning and call {@code submit_plan} for user
 * review before performing mutations.
 *
 * <p>
 * Inspired by Claude Code's plan-mode: forces an explicit read-then-write separation so the model commits to a plan
 * visible to the user before touching the workspace.
 * </p>
 */
@Component
@ProjectRuntime
public class PlanModeService {

    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final Path plans;

    /** In-memory instance for isolated callers; the Runtime uses its workspace data directory. */
    public PlanModeService() { plans = null; }

    @Autowired
    public PlanModeService(AgentConfig config) {
        plans = Path.of(config.getDataDir()).toAbsolutePath().normalize().resolve("agent/plans");
    }

    private Path marker(String threadId) {
        if (!Ids.isSafe(threadId)) throw new IllegalArgumentException("invalid plan threadId");
        return plans == null ? null : plans.resolve(threadId).resolve("review-required");
    }

    public synchronized boolean isActive(String threadId) {
        if (threadId == null) return false;
        Path marker = marker(threadId);
        return active.contains(threadId) || marker != null && Files.isRegularFile(marker);
    }

    public synchronized void enable(String threadId) {
        if (threadId != null) {
            Path marker = marker(threadId);
            // Fail closed in the current process even when durable storage is unavailable.
            active.add(threadId);
            if (marker != null) {
                try {
                    Files.createDirectories(marker.getParent());
                    if (!Files.exists(marker)) Files.writeString(marker, "review-required\n");
                } catch (IOException failure) { throw new UncheckedIOException("Cannot persist plan review gate", failure); }
            }
        }
    }

    public synchronized void disable(String threadId) {
        if (threadId != null) {
            Path marker = marker(threadId);
            if (marker != null) {
                try { Files.deleteIfExists(marker); }
                catch (IOException failure) { throw new UncheckedIOException("Cannot persist plan acceptance", failure); }
            }
            active.remove(threadId);
        }
    }

    public void purge(String threadId) {
        disable(threadId);
    }
}
