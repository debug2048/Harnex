package com.harnex.web.api.runtime;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.harnex.command.SlashCommand;
import com.harnex.command.SlashCommandRegistry;
import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.config.RuntimeDomainPaths;
import com.harnex.contract.runtime.RuntimeMetaExtension;
import com.harnex.workspace.WorkspaceContext;
import com.harnex.workspace.WorkspaceIdentity;
import com.harnex.process.CommandExecutor;
import com.harnex.workspace.WorkspacePathResolver;
import com.harnex.skill.SkillLoader;
import com.harnex.tool.Tool;
import com.harnex.tool.ToolRegistry;
import com.harnex.toolset.ToolsetRegistry;

@RestController
@ProjectRuntime
@RequestMapping("/api/v1")
public class MetaController implements RuntimeMetaExtension {

    private final ToolRegistry tools;
    private final ToolsetRegistry toolsets;
    private final AgentConfig cfg;
    private final SkillLoader skillLoader;
    private final WorkspaceContext workspace;
    private final SlashCommandRegistry commands;
    private final WorkspaceIdentity identity;
    private final WorkspacePathResolver paths;
    private CommandExecutor commandExecutor;

    @Autowired
    void execution(CommandExecutor executor) { this.commandExecutor = executor; }

    private Map<String, Object> executionStatus() {
        return commandExecutor == null ? Map.of("environment", cfg.getExecution().getEnvironment(),
            "sandbox", cfg.getExecution().getSandboxMode()) : commandExecutor.describe();
    }

    public MetaController(ToolRegistry tools, ToolsetRegistry toolsets, AgentConfig cfg, SkillLoader skillLoader,
        WorkspaceContext workspace, SlashCommandRegistry commands, WorkspaceIdentity identity,
        WorkspacePathResolver paths) {
        this.tools = tools;
        this.toolsets = toolsets;
        this.cfg = cfg;
        this.skillLoader = skillLoader;
        this.workspace = workspace;
        this.commands = commands;
        this.identity = identity;
        this.paths = paths;
    }

    @GetMapping("/tools")
    public List<Map<String, Object>> tools() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Tool t : tools.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.getName());
            m.put("description", t.getDescription() == null ? "" : t.getDescription());
            // Single capability list replaces the legacy readOnly/destructive/concurrencySafe
            // triple. Frontend / external governance consumers derive their own predicates from
            // this Set so we stay forward-compatible when new capabilities are added.
            m.put("capabilities", t.capabilities().stream().map(Enum::name).sorted().toList());
            out.add(m);
        }
        return out;
    }

    @GetMapping("/toolsets")
    public Map<String, Collection<String>> toolsets() {
        // Iteration order: configured harnex.toolsets.default-set entries first (preserved), then
        // the remaining presets in yml-declared order. The WebUI picks the first key as the
        // initial dropdown selection, so this guarantees the chat composer pre-selects whatever
        // the operator declared as the workspace default rather than whichever preset happens
        // to be defined first in yml.
        Map<String, ? extends Collection<String>> all = toolsets.allPresets();
        Map<String, Collection<String>> out = new LinkedHashMap<>();
        List<String> def = cfg.getToolsets() == null ? null : cfg.getToolsets().getDefaultSet();
        if (def != null) {
            for (String k : def) {
                Collection<String> v = all.get(k);
                if (v != null) {
                    out.put(k, new ArrayList<>(v));
                }
            }
        }
        all.forEach((k, v) -> {
            if (!out.containsKey(k)) {
                out.put(k, new ArrayList<>(v));
            }
        });
        return out;
    }

    @GetMapping("/skills")
    public List<Map<String, Object>> skills() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var s : skillLoader.list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.getName());
            m.put("description", s.getDescription() == null ? "" : s.getDescription());
            m.put("source", s.getSource());
            m.put("overridden", s.isOverridden());
            m.put("enabled", s.isEnabled());
            m.put("effective", s.isEffective());
            m.put("path", s.getPath() == null ? "" : s.getPath().toString());
            try {
                m.put("bytes", s.getPath() != null && Files.exists(s.getPath()) ? Files.size(s.getPath()) : 0L);
                m.put("modifiedAt", s.getPath() != null && Files.exists(s.getPath())
                    ? Files.getLastModifiedTime(s.getPath()).toMillis() : 0L);
            } catch (Exception ignored) {
                m.put("bytes", 0L);
                m.put("modifiedAt", 0L);
            }
            out.add(m);
        }
        return out;
    }

    @PutMapping("/skills/{name}")
    public ResponseEntity<?> setSkillEnabled(@PathVariable String name, @RequestBody Map<String, Object> body) {
        try {
            String source = String.valueOf(body.getOrDefault("source", ""));
            boolean enabled = Boolean.parseBoolean(String.valueOf(body.getOrDefault("enabled", "true")));
            var skill = skillLoader.setEnabled(name, source, enabled);
            return ResponseEntity.ok(Map.of("name", skill.getName(), "source", skill.getSource(),
                "overridden", skill.isOverridden(), "enabled", skill.isEnabled(), "effective", skill.isEffective()));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404).body(Map.of("error",
                Map.of("code", "skill_not_found", "message", e.getMessage())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error",
                Map.of("code", "bad_request", "message", e.getMessage())));
        }
    }

    @PostMapping("/skills/rescan")
    public Map<String, Object> rescanSkills() {
        skillLoader.rescan();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", skillLoader.list().size());
        m.put("skills", skillLoader.list().stream().map(s -> s.getName()).toList());
        return m;
    }

    /** Slash-command catalog for the WebUI / TUI autocomplete. Both entrypoints expand commands server-side. */
    @GetMapping("/commands")
    public List<Map<String, Object>> commands() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SlashCommand c : commands.list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.name());
            m.put("description", c.description() == null ? "" : c.description());
            m.put("argHint", c.argHint() == null ? "" : c.argHint());
            if (c.completion() != null) {
                m.put("completion", c.completion());
            }
            out.add(m);
        }
        return out;
    }

    @GetMapping("/hooks")
    public List<Map<String, Object>> hooks() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var rule : cfg.getHooks().getRules()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", rule.getName());
            m.put("phase", rule.getPhase());
            m.put("toolPattern", rule.getToolPattern());
            m.put("action", rule.getAction());
            m.put("disabled", rule.isDisabled());
            out.add(m);
        }
        return out;
    }

    /**
     * Workspace context: which AGENTS.md (if any) is active and a short preview. Powers the statusline "workspace"
     * indicator and the settings page.
     */
    @GetMapping("/workspace")
    public Map<String, Object> workspace() {
        Map<String, Object> m = new LinkedHashMap<>();
        var root = paths.getProjectRoot();
        var fileName = root.getFileName();
        m.put("workspaceId", identity.workspaceId());
        m.put("instanceId", identity.instanceId());
        m.put("runtimeDomainId", cfg.getRuntimeDomainId() == null || cfg.getRuntimeDomainId().isBlank()
            ? RuntimeDomainPaths.id(cfg) : cfg.getRuntimeDomainId());
        m.put("name", fileName == null ? root.toString() : fileName.toString());
        m.put("root", root.toString());
        m.put("dataDir", paths.getDataDir().toString());
        // A Runtime reached directly (Docker/HMR/Tauri/IDE embedded host) is a fixed
        // single-workspace projection. The standalone browser uses Supervisor APIs
        // instead and is the only host that exposes workspace switching.
        m.put("hostMode", "fixed");
        m.put("agentsMdSource",
            workspace.getResolvedSource() == null ? null : workspace.getResolvedSource().toString());
        String md = workspace.getAgentsMd();
        m.put("agentsMdBytes", md == null ? 0 : md.getBytes(StandardCharsets.UTF_8).length);
        try {
            var source = workspace.getResolvedSource();
            m.put("agentsMdModifiedAt", source != null && Files.exists(source)
                ? Files.getLastModifiedTime(source).toMillis() : 0L);
        } catch (Exception ignored) {
            m.put("agentsMdModifiedAt", 0L);
        }
        m.put("preview", md == null ? "" : md.length() <= 400 ? md : md.substring(0, 400) + "\u2026");
        return m;
    }

    /** Re-read AGENTS.md from disk on demand (e.g. after `harnex init`). */
    @PostMapping("/workspace/reload")
    public Map<String, Object> reloadWorkspace() {
        workspace.reload();
        return workspace();
    }
    @Override public Map<String, Object> diagnostics() {
        Map<String, Object> values = new LinkedHashMap<>(statusline());
        values.put("toolCount", tools.all().size());
        values.put("toolsetPresets", toolsets.allPresets().keySet());
        values.put("skillCount", skillLoader.list().size());
        values.put("approvalMode", cfg.getApproval().getMode());
        return values;
    }
    @Override public Map<String, Object> statusline() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("execution", executionStatus());
        values.put("workspace", workspace.getResolvedSource() == null ? null : workspace.getResolvedSource().toString());
        values.put("approval", cfg.getApproval().getMode());
        return values;
    }

}
