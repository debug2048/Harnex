package com.harnex.web.api.runtime;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.harnex.config.AgentConfig;
import com.harnex.config.RuntimeProfile;
import com.harnex.remote.RuntimeBuild;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Local API capability discovery (docs/03 §3.14). Public (no auth): clients must be able to probe the runtime and
 * negotiate capabilities BEFORE authenticating — the endpoint exposes no user data, only the protocol contract.
 *
 * <p>
 * Version semantics: the Local API v1 contract is frozen at {@code 1.0}. Evolution inside the v1 path is additive;
 * clients must feature-detect optional surfaces via {@code capabilities}, never via version comparison.
 * </p>
 */
@RestController
public class ProtocolController {
    @Autowired(required = false)
    private AgentConfig config = new AgentConfig();

    public static final String PROTOCOL_VERSION = "1.0";

    public static final List<String> CAPABILITIES = List.of(
        // Execution
        "runs", "runs.inline-stream", "runs.events.after-seq", "runs.cancel", "runs.inputs",
        "runs.inputs.steer", "run.projection", "run.usage", "agent.execution",
        "scope.identity.v1", "execution.spec.v1", "conversation.history.v1", "conversation.submissions.v1", "artifacts.v1",
        "image.operations.v1", "run.presentation.v1", "model.calls.v1", "results.preview.v1",
        // Interactions (unified pending + resolve)
        "interactions", "interactions.approval", "interactions.plan-review", "interactions.question",
        "interactions.audit", "activity.semantic",
        // Conversation + context
        "threads.todos", "snapshots", "skills",
        "workspace", "workspace.identity", "workspace.fixed-host", "goal", "goal.control", "goal.history",
        "goal.recovery", "goal.criteria", "subagent.activity",
        "files.read", "files.search", "files.open", "changes.read", "changes.diff",
        "config.read", "config.write", "skills.toggle", "memory.scopes",
        "plan-mode",
        // Integration surfaces
        "mcp.client", "mcp.server", "ide.clients.v1", "gateway", "cron",
        // System
        "boot-token", "diagnostics", "openapi", "runtime.build", "runtime.idle-upgrade");

    private static final String RUNTIME_VERSION = resolveRuntimeVersion();

    @Schema(requiredProperties = {"name", "version", "runtimeVersion", "capabilities", "runtimeProfile"})
    public record ProtocolInfo(String name, String version, String runtimeVersion, String runtimeSha256, List<String> capabilities,
        RuntimeProfile runtimeProfile) {}

    @GetMapping("/api/v1/protocol")
    public ProtocolInfo protocol() {
        boolean personal = config.getRuntimeProfile() == RuntimeProfile.PERSONAL;
        List<String> capabilities = personal ? CAPABILITIES.stream().filter(value -> !Set.of(
            "agent.execution", "runs.inputs", "runs.inputs.steer", "interactions", "interactions.approval", "interactions.plan-review",
            "interactions.question", "interactions.audit", "threads.todos", "snapshots", "skills", "workspace", "workspace.identity",
            "workspace.fixed-host", "goal", "goal.control", "goal.history", "goal.recovery", "goal.criteria", "subagent.activity",
            "files.read", "files.search", "files.open", "changes.read", "changes.diff", "skills.toggle", "memory.scopes", "plan-mode",
            "mcp.client", "mcp.server", "ide.clients.v1", "gateway").contains(value)).toList() : CAPABILITIES;
        return new ProtocolInfo("harnex-local-api", PROTOCOL_VERSION, RUNTIME_VERSION, RuntimeBuild.sha256(), capabilities, config.getRuntimeProfile());
    }

    private static String resolveRuntimeVersion() {
        Package p = ProtocolController.class.getPackage();
        String v = p == null ? null : p.getImplementationVersion();
        return v == null || v.isBlank() ? "1.0.0-SNAPSHOT" : v;
    }
}
