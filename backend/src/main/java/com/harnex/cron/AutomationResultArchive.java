package com.harnex.cron;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import com.harnex.config.AgentConfig;
import com.harnex.conversation.ContentPart;
import com.harnex.run.*;
import com.harnex.workspace.WorkspaceReadService;
import com.harnex.usage.UsageLedger;
import com.harnex.util.Ids;
import lombok.extern.slf4j.Slf4j;

/** Capture file facts before the automation releases its execution reservation. Later foreground writes stay separate. */
@Component @Slf4j
public final class AutomationResultArchive {
    private final Path root;
    private final UsageLedger usage;
    private final ObjectProvider<WorkspaceReadService> workspace;
    public AutomationResultArchive(AgentConfig config, UsageLedger usage, ObjectProvider<WorkspaceReadService> workspace) {
        this.root = Path.of(config.getDataDir(), "automation/execution-results"); this.usage = usage; this.workspace = workspace;
    }
    public void capture(RunHandle run, List<ContentPart> content, RunStatus status, String detail) {
        if (run.getOrigin().kind() != RunOrigin.Kind.AUTOMATION) return;
        try {
            List<AutomationExecutionService.Change> changes = new ArrayList<>();
            WorkspaceReadService project = workspace.getIfAvailable();
            if (project != null) try {
                var entries = AutomationStore.JSON.valueToTree(project.changes(run.getRunId(), run.getThreadId())).path("entries");
                for (var entry : entries) changes.add(new AutomationExecutionService.Change(entry.path("path").asText(),
                    entry.path("status").asText(), entry.path("added").asInt(), entry.path("removed").asInt()));
            } catch (RuntimeException unavailable) { detail = "The text result was saved, but file changes could not be captured: " + unavailable.getMessage(); }
            String text = content.stream().filter(part -> part.type() == ContentPart.Type.TEXT).map(ContentPart::text)
                .collect(Collectors.joining("\n\n"));
            AutomationStore.writePrivate(root.resolve(run.getRunId() + ".json"), new AutomationExecutionService.Result(
                run.getRunId(), run.getThreadId(), status.name(), true, run.getCreatedAt(), System.currentTimeMillis(), text,
                detail, usage.summary(run.getRunId()), List.copyOf(changes)));
        } catch (Exception failure) {
            // The authoritative conversation is already committed. Never repeat its work for an auxiliary snapshot failure.
            log.warn("Could not capture automation result {}: {}", run.getRunId(), failure.toString());
        }
    }
    public AutomationExecutionService.Result read(String runId) {
        if (!Ids.isSafeResourceId(runId)) throw new IllegalArgumentException("Invalid runId");
        Path path = root.resolve(runId + ".json");
        try { return Files.exists(path) ? AutomationStore.JSON.readValue(path.toFile(), AutomationExecutionService.Result.class) : null; }
        catch (Exception failure) { log.warn("Could not read automation result {}: {}", runId, failure.toString()); return null; }
    }
}
