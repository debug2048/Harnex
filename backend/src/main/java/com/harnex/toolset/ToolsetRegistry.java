package com.harnex.toolset;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.mcp.McpToolAdapter;
import com.harnex.tool.ToolRegistry;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * Toolset presets (Hermes-inspired). Maps preset names to tool-name sets; `resolve([presets])` returns the union.
 *
 * <p>
 * Concurrency: presets may be mutated at runtime by {@link com.harnex.mcp.McpRegistry} as new MCP tools come online
 * (initial discovery + reconnect / health-check loops). Reads happen on every AgentLoop invocation (request threads).
 * Backing structures are therefore concurrent-safe so a background MCP refresh cannot
 * {@link ConcurrentModificationException} a foreground iterator and cannot publish a partially-updated preset view.
 */
@Slf4j
@Component
@ProjectRuntime
public class ToolsetRegistry {

    private final AgentConfig cfg;
    private final ToolRegistry registry;
    // MCP membership follows connection lifetime; configured presets follow task settings lifetime.
    private final Set<String> allAdditions = new CopyOnWriteArraySet<>();
    private final ConcurrentMap<String, Set<String>> additions = new ConcurrentHashMap<>();

    public ToolsetRegistry(AgentConfig cfg, ToolRegistry registry) {
        this.cfg = cfg;
        this.registry = registry;
    }

    @PostConstruct
    public void init() {
        basePresets();
    }

    private Map<String, Set<String>> basePresets() {
        return cfg.derivedSettings(this, ToolsetRegistry::compilePresets);
    }

    private static Map<String, Set<String>> compilePresets(AgentConfig configuration) {
        Map<String, Set<String>> presets = new LinkedHashMap<>();
        Map<String, List<String>> configured = configuration.getToolsets().getPresets();
        if (configured != null && !configured.isEmpty()) {
            configured.forEach((k, v) -> presets.put(k, new CopyOnWriteArraySet<>(v)));
        }
        if (presets.isEmpty()) {
            // Three user-facing modes — must stay in lock-step with agent-config.yml's `presets` block.
            // - read : 只看 (file_read / glob / grep / web_fetch + 只读元工具)
            // - inspect : 巡检 (read + bash, 用于 SRE 排查)
            // - act : 全做 (inspect + 写文件 / 编辑 / 子代理 / 记忆写入)
            // Names match the user mental model (看 / 巡检 / 改) so an IM user picking `/toolset`
            // doesn't have to guess between two synonyms. Operators may add custom preset names
            // in agent-config.yml `harnex.toolsets.presets`; those override these defaults entirely.
            Set<String> read =
                setOf("file_read", "glob", "grep", "web_fetch", "web_search", "memory_list", "skill_list", "skill_read",
                    "session_search", "session_read", "session_fact", "self_inspect", "todo_read", "todo_write", "lesson_record", "ask_user");
            Set<String> inspect = setOf("file_read", "glob", "grep", "web_fetch", "web_search", "bash", "memory_list",
                "skill_list", "skill_read", "session_search", "session_read", "session_fact", "self_inspect", "todo_read", "todo_write",
                "lesson_record", "ask_user");
            Set<String> act = setOf("bash", "file_read", "file_write", "file_edit", "multi_edit", "glob", "grep",
                "web_fetch", "web_search", "memory_remember", "memory_list", "memory_forget", "skill_list",
                "skill_read", "session_search", "session_read", "session_fact", "delegate", "self_inspect", "todo_read", "todo_write",
                "submit_plan", "update_goal", "verify", "lesson_record", "ask_user", "image_generate", "image_inspect", "image_export");
            presets.put("read", new CopyOnWriteArraySet<>(read));
            presets.put("inspect", new CopyOnWriteArraySet<>(inspect));
            presets.put("act", new CopyOnWriteArraySet<>(act));
        }
        // vscode: VS Code extension client (docs/03 §3.32.4). Seeded AFTER the config/default
        // block because configured presets fully replace the built-in three — the extension
        // must still find this preset on a stock install whose YAML defines its own presets
        // (the packaged agent-config.yml does). Base capability equals `act` (configured or
        // built-in); the editor-bridge MCP tools (editor_get_selection / ...) join at runtime
        // via harnex.mcp.servers[].expose-to-presets=[vscode]. An explicitly configured
        // `vscode` preset wins — this only fills the gap.
        if (!presets.containsKey("vscode")) {
            Set<String> base = presets.get("act");
            presets.put("vscode", new CopyOnWriteArraySet<>(base != null ? base : Set.<String>of()));
        }
        return presets;
    }

    public Set<String> listPresets() {
        return new LinkedHashSet<>(basePresets().keySet());
    }

    public Map<String, Set<String>> allPresets() {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        basePresets().forEach((name, tools) -> {
            Set<String> merged = new LinkedHashSet<>(tools);
            merged.addAll(allAdditions);
            merged.addAll(additions.getOrDefault(name, Set.of()));
            out.put(name, merged);
        });
        return Collections.unmodifiableMap(out);
    }

    public Set<String> resolve(List<String> names) {
        return selectedPresets(names).values().stream().flatMap(Collection::stream)
            .filter(name -> registry.get(name) != null).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Effective presets before filtering undiscovered tools; selecting them never starts external processes. */
    public Map<String, Set<String>> selectedPresets(List<String> names) {
        Map<String, Set<String>> presets = new LinkedHashMap<>(allPresets());
        // Private presets are a view of the current vscode preset, not a growing catalogue of past windows.
        if (names != null) names.stream().filter(this::isClientPreset).forEach(name -> {
            Set<String> tools = new LinkedHashSet<>(presets.getOrDefault("vscode", Set.of()));
            tools.removeIf(this::isEditorBridgeTool);
            tools.addAll(additions.getOrDefault(name, Set.of()));
            presets.put(name, tools);
        });
        // Distinguish "caller didn't ask for anything" from "caller asked for X, Y, Z but
        // none exist". The former is the normal default path (CLI / cron without explicit
        // toolsets, fresh chat session). The latter is a misconfiguration (deleted preset
        // still referenced by a saved thread, cron job pointing at a renamed toolset, typo'd
        // /toolset readonyl) — silently widening to `default` here is a privilege-escalation
        // risk because `default` includes bash/file_write/file_edit/delegate.
        boolean implicitDefault = (names == null || names.isEmpty());
        if (implicitDefault) {
            List<String> def = cfg.getToolsets().getDefaultSet();
            names = def == null || def.isEmpty() ? List.of("act") : def;
        }
        Map<String, Set<String>> out = new LinkedHashMap<>();
        int matched = 0;
        List<String> unknown = new ArrayList<>();
        for (String n : names) {
            Set<String> p = presets.get(n);
            if (p != null) {
                matched++;
                out.put(n, new LinkedHashSet<>(p));
            } else {
                unknown.add(n);
            }
        }
        // Fallback policy:
        // * implicitDefault path AND nothing matched → fall through to default-set lookup
        // so a missing/empty configured default still produces a usable toolset (the
        // "fresh install with no presets" case).
        // * caller passed names explicitly but EVERY one was unknown → DO NOT fall back to
        // `act` (which contains bash/file_write/file_edit/delegate). That would turn
        // "/toolset reaad" or a thread persisted under a deleted preset into a silent
        // privilege escalation. Fall back to `read` if it exists, otherwise to an
        // empty set (fail-closed). The agent will report "no tools" and the operator
        // gets a WARN instead of unintended bash access.
        if (matched == 0) {
            if (implicitDefault) {
                // The configured default-set itself was unknown — last-resort to the built-in
                // "act" preset. This is the same pre-existing behaviour for fresh installs.
                Set<String> p = presets.get("act");
                if (p != null) {
                    out.put("act", new LinkedHashSet<>(p));
                    log.warn("default-set {} not registered; using built-in 'act' preset", names);
                } else {
                    log.warn("default-set {} not registered and no built-in fallback; returning empty toolset", names);
                }
            } else {
                Set<String> ro = presets.get("read");
                if (ro != null) {
                    out.put("read", new LinkedHashSet<>(ro));
                    log.warn("toolset names {} not registered; SECURITY: refusing to widen to 'act', "
                        + "falling back to 'read' preset. Fix the caller (thread/cron/CLI).", unknown);
                } else {
                    log.warn("toolset names {} not registered and no 'read' preset available; "
                        + "SECURITY: returning empty toolset (fail-closed) instead of widening to 'act'.", unknown);
                }
            }
        } else if (!unknown.isEmpty()) {
            // Partial match: the matched presets stand, but record which names were dropped
            // so the operator can clean up the caller without surprise side-effects.
            log.warn("toolset names {} not registered; ignored (matched presets still applied)", unknown);
        }
        return out;
    }

    /** Add tool names to every existing preset (used by MCP to auto-include discovered tools). */
    public void addToAllPresets(Collection<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty())
            return;
        allAdditions.addAll(toolNames);
    }

    /**
     * Add tool names to a specific subset of presets. Used by MCP so an operator can opt in (or stay opted out) on a
     * per-server basis instead of every MCP tool landing in {@code read} / {@code inspect} unconditionally — that
     * previously turned every connected MCP server into a read-only safety-boundary bypass.
     * <p>
     * Unknown preset names are silently ignored with a WARN — they correspond to operator typos in
     * {@code harnex.mcp.servers[].expose-to-presets} and we don't want a typo to fail-open by widening to {@code act}
     * (the previous {@link #addToAllPresets} behaviour).
     */
    public void addToPresets(Collection<String> toolNames, Collection<String> presetNames) {
        if (toolNames == null || toolNames.isEmpty())
            return;
        if (presetNames == null || presetNames.isEmpty())
            return;
        for (String presetName : presetNames) {
            if (!basePresets().containsKey(presetName) && !isClientPreset(presetName)) {
                log.warn("Cannot expose MCP tools to preset '{}' — preset does not exist (check "
                    + "harnex.toolsets.presets / harnex.mcp.servers[].expose-to-presets)", presetName);
                continue;
            }
            additions.compute(presetName, (name, existing) -> {
                Set<String> tools = existing == null ? new CopyOnWriteArraySet<>() : existing;
                tools.addAll(toolNames); return tools;
            });
        }
    }

    /**
     * Remove tool names from every preset. Used by MCP reconnect when an upstream server dropped a tool — without this
     * the tool name lingers in {@link #allPresets()} and the Settings UI / self_inspect would advertise a "ghost" tool.
     * {@link #resolve} already filters by {@code registry.get(t) != null} so the agent can't dispatch to it, but the
     * metadata view bypasses that filter.
     */
    public void removeFromPresets(Collection<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty())
            return;
        allAdditions.removeAll(toolNames);
        for (String name : additions.keySet()) additions.computeIfPresent(name, (key, tools) -> {
            tools.removeAll(toolNames); return tools.isEmpty() ? null : tools;
        });
    }

    /** Private connection presets never enter the user catalogue or the Delegate tool's preset enum. */
    public boolean isClientPreset(String name) { return name != null && name.matches("ide-vscode-[A-Za-z0-9-]{1,80}"); }

    private boolean isEditorBridgeTool(String name) {
        if (!(registry.get(name) instanceof McpToolAdapter tool)) return false;
        AgentConfig.McpServer ownerBridge = cfg.getRuntimeMcpBridge();
        return isClientPreset(tool.getServerName())
            || ownerBridge != null && Objects.equals(ownerBridge.getName(), tool.getServerName());
    }

    private static Set<String> setOf(String... s) {
        return new CopyOnWriteArraySet<>(Arrays.asList(s));
    }
}
