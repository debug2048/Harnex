package com.harnex.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.core.PromptBuilder;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Workspace-aware context loader \u2014 reads an {@code AGENTS.md} from the project workspace and exposes its
 * (size-capped) content to {@link PromptBuilder} for injection into the system prompt.
 *
 * <p>
 * Discovery order (first hit wins):
 * <ol>
 * <li>Env var / system property {@code HARNEX_WORKSPACE} (treated as a directory path).</li>
 * <li>JVM working directory ({@code user.dir}).</li>
 * <li>{@code ~/.harnex/AGENTS.md} (user-global fallback).</li>
 * </ol>
 *
 * <p>
 * The first file named {@code AGENTS.md} found at the directory root is loaded; nothing is recursed. Loaded once at
 * startup; call {@link #reload()} to re-read on demand.
 *
 * <p>
 * Hard cap: {@link #MAX_AGENTS_MD_BYTES} bytes; oversize files are truncated with a marker so a runaway AGENTS.md
 * cannot blow out the system prompt budget.
 */
@Slf4j
@Component
@ProjectRuntime
public class WorkspaceContext {

    public static final int MAX_AGENTS_MD_BYTES = 16 * 1024;

    @Value("${HARNEX_WORKSPACE:}")
    private String configuredWorkspace;

    private final AgentConfig cfg;

    public WorkspaceContext(AgentConfig cfg) {
        this.cfg = cfg;
    }

    @Getter
    private volatile String agentsMd = "";
    @Getter
    private volatile Path resolvedSource = null;

    @PostConstruct
    void init() {
        reload();
    }

    /** Re-read AGENTS.md from disk. Safe to call at runtime (e.g. exposed via {@code POST /api/workspace/reload}). */
    public synchronized void reload() {
        Path found = locate();
        if (found == null) {
            agentsMd = "";
            resolvedSource = null;
            log.debug("WorkspaceContext: no AGENTS.md found");
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(found);
            if (bytes.length > MAX_AGENTS_MD_BYTES) {
                // Truncate at the last newline within the budget so we don't cut a Markdown line
                // (or, worse, a multi-byte UTF-8 sequence) in half. If no newline is present in the
                // last half-budget, fall back to the nearest UTF-8 codepoint boundary so we never
                // emit U+FFFD into the system prompt.
                int cut = MAX_AGENTS_MD_BYTES;
                for (int i = MAX_AGENTS_MD_BYTES - 1; i > MAX_AGENTS_MD_BYTES / 2; i--) {
                    if (bytes[i] == (byte)'\n') {
                        cut = i;
                        break;
                    }
                }
                if (cut == MAX_AGENTS_MD_BYTES) {
                    // Walk back over UTF-8 continuation bytes (10xxxxxx). Stops at the first byte
                    // that is either ASCII (0xxxxxxx) or a multi-byte leader (11xxxxxx) — i.e. a
                    // safe codepoint boundary. Bounded by ≤4 iterations (max UTF-8 sequence length).
                    while (cut > 0 && (bytes[cut] & 0xC0) == 0x80) {
                        cut--;
                    }
                }
                String truncated = new String(bytes, 0, cut, StandardCharsets.UTF_8) + "\n\n[...AGENTS.md truncated at "
                    + cut + "/" + bytes.length + " bytes...]";
                agentsMd = truncated;
                log.warn("AGENTS.md from {} truncated from {} to {} bytes", found, bytes.length, cut);
            } else {
                agentsMd = new String(bytes, StandardCharsets.UTF_8);
            }
            resolvedSource = found;
            log.info("WorkspaceContext loaded AGENTS.md from {} ({} bytes)", found, bytes.length);
        } catch (IOException e) {
            log.warn("WorkspaceContext failed to read {}: {}", found, e.getMessage());
            agentsMd = "";
            resolvedSource = null;
        }
    }

    /** Render the AGENTS.md content as a system-prompt section (prefixed) or empty string when absent. */
    public String renderForPrompt() {
        if (agentsMd == null || agentsMd.isBlank()) {
            return "";
        }
        return "\n## Workspace Context (AGENTS.md)\n" + agentsMd + "\n";
    }

    private Path locate() {
        // Mirror WorkspacePathResolver's root resolution EXACTLY (harnex.workspace.root → HARNEX_WORKSPACE → user.dir)
        // so
        // the AGENTS.md the model reads always describes the SAME directory its file tools can edit. When an explicit
        // root is configured we anchor discovery there and NEVER fall through to the process cwd or a different
        // workspace — otherwise the model could read project A's AGENTS.md while editing project B.
        String cfgRoot = cfg != null && cfg.getWorkspace() != null ? cfg.getWorkspace().getRoot() : null;
        String explicitRoot = (cfgRoot != null && !cfgRoot.isBlank()) ? cfgRoot
            : (configuredWorkspace != null && !configuredWorkspace.isBlank()) ? configuredWorkspace : null;
        if (explicitRoot != null) {
            Path p = Paths.get(explicitRoot, "AGENTS.md");
            if (Files.isRegularFile(p))
                return p;
            // Explicit root set but no project-level AGENTS.md: only the user-global file may still apply; do NOT
            // borrow another workspace's / the cwd's AGENTS.md.
            return globalAgentsMd();
        }
        // No explicit root: anchor on the process working directory (same user.dir fallback the resolver uses),
        // then the user-global file.
        Path cwd = Paths.get(System.getProperty("user.dir", ".")).resolve("AGENTS.md");
        if (Files.isRegularFile(cwd))
            return cwd;
        return globalAgentsMd();
    }

    /** The user-global {@code ~/.harnex/AGENTS.md} (generic personal instructions), or {@code null} if absent. */
    private static Path globalAgentsMd() {
        String home = System.getProperty("user.home");
        if (home != null) {
            Path global = Paths.get(home, ".harnex", "AGENTS.md");
            if (Files.isRegularFile(global))
                return global;
        }
        return null;
    }
}
