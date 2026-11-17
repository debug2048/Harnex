package com.harnex.tool.approval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.core.InteractionBroker;
import com.harnex.interaction.InteractionStateBoundary;
import com.harnex.model.ToolCall;
import com.harnex.session.SessionStore;
import com.harnex.workspace.WorkspacePathResolver;
import com.harnex.workspace.WorkspacePathResolver.Zone;
import com.harnex.tool.ToolContext;
import com.harnex.process.ShellCommandClassifier;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Approval service (Hermes-style). Tri-state decision: ALLOW / ASK / DENY.
 *
 * Dangerous-pattern blacklist + per-thread whitelist of previously approved calls. Whitelists are automatically purged
 * when a thread is deleted via SessionStore.
 */
@Slf4j
@Component
@ProjectRuntime
public class ApprovalService {
    @Autowired(required = false)
    private InteractionStateBoundary interactionBoundary = InteractionStateBoundary.DIRECT;
    private <T> T change(String threadId, Supplier<T> mutation) { return interactionBoundary.update(threadId, "approval", mutation); }

    public enum Decision {
        ALLOW, ASK, DENY
    }

    private final AgentConfig.Approval cfg;
    private final SessionStore sessionStore;
    private final WorkspacePathResolver workspacePaths;
    private final List<Pattern> dangerous = new ArrayList<>();
    private final ConcurrentMap<String, ConcurrentMap<String, Boolean>> whitelist = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PendingDecision> pending = new ConcurrentHashMap<>();
    /** B7: timer that prunes pending approvals abandoned by the user (browser closed, etc). */
    private ScheduledExecutorService cleaner;
    /**
     * Pending approval prompts beyond this age are dropped — 1h is plenty for an interactive user to react, and short
     * enough that we don't accumulate phantom prompts after restarts.
     */
    private static final long PENDING_TTL_MS = 60L * 60 * 1000;

    /**
     * Wraps a CompletableFuture with its creation time, plus the tool name captured from the tool-call at ASK time.
     * Storing the tool name SERVER-SIDE means the {@code recordDecision} controller no longer has to trust whatever
     * {@code tool} string the SPA puts in the body — a malicious or stale client could otherwise widen permissions for
     * a different tool than the one actually pending (e.g. body says "file_read" while the pending call is "bash").
     */
    private static final class PendingDecision {
        final CompletableFuture<Decision> future = new CompletableFuture<>();
        final long createdAt = System.currentTimeMillis();
        final String toolName;
        final String threadId;
        final String callId;
        /** Raw JSON arguments captured at ASK time so pending-list clients can render what is being approved. */
        final String arguments;
        /** Outside-write ASK is deliberately one-shot and must never become a tool-wide whitelist. */
        final boolean outsideWrite;
        final String sourceCallId;
        final String subagentThreadId;

        PendingDecision(String threadId, String callId, String toolName, String arguments, boolean outsideWrite,
            String sourceCallId, String subagentThreadId) {
            this.threadId = threadId;
            this.callId = callId;
            this.toolName = toolName;
            this.arguments = arguments;
            this.outsideWrite = outsideWrite;
            this.sourceCallId = sourceCallId;
            this.subagentThreadId = subagentThreadId;
        }
    }

    /** Read-only view of a not-yet-resolved approval, for the unified interactions pending API (docs/03 §3.14). */
    public record ApprovalPendingView(String threadId, String callId, String toolName, String arguments,
        boolean outsideWrite,
        long createdAt, String sourceCallId, String subagentThreadId) {
    }

    /**
     * Build the pending-approval map key. Indexing on {@code callId} alone meant two concurrent threads that happen to
     * mint the same callId would share a single PendingDecision — the first user click would unblock the WRONG
     * conversation. {@code (threadId, callId)} scopes the slot per-thread so cross-thread collisions are impossible.
     * The {@code \u0000} separator keeps {@link com.harnex.util.Ids#isSafe} alphanumeric ids from ever accidentally
     * producing the same composite for different (tid, cid) pairs.
     */
    private static String pendingKey(String threadId, String callId) {
        return (threadId == null ? "" : threadId) + "\u0000" + (callId == null ? "" : callId);
    }

    @Autowired
    public ApprovalService(AgentConfig cfg, SessionStore sessionStore, WorkspacePathResolver workspacePaths) {
        this.cfg = cfg.getApproval();
        this.sessionStore = sessionStore;
        this.workspacePaths = workspacePaths;
        // Hardened baseline. The previous behaviour was "user list, if non-empty, REPLACES
        // these defaults" — a footgun, because the shipped agent-config.yml carried a legacy
        // (narrower) list and so a stock production startup silently lost the strictly stronger
        // patterns added here (broadened rm -rf coverage that catches /home, chmod -R 777 of
        // root-ish paths, fork bomb tolerant of whitespace). Now the defaults are always
        // installed; the user list is APPENDED. An operator who needs to RELAX a default can
        // set harnex.approval.replace-default-patterns=true and supply the full list explicitly.
        List<String> defaults = List.of(
            // Recursive deletion of common system roots / home / etc — broadened from the original
            // "rm -rf /(?![a-z])" which let "rm -rf /home" through.
            "(?i)\\brm\\s+(?:-[rRfF]+\\s+)+(?:/|/(?:home|etc|var|usr|bin|sbin|boot|lib|root|opt|sys|proc|Users|System|Library|Applications|Volumes|private)\\b|~/?$|\\$HOME)",
            "(?i)\\bmkfs\\.", "(?i)\\bdd\\s+.*\\bof=/dev/[sh]d[a-z]",
            // Fork bomb — match flexibly around whitespace.
            ":\\s*\\(\\s*\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*\\}\\s*;\\s*:",
            // curl|sh / wget|sh remote-execution pipe.
            "(?i)\\b(?:curl|wget)\\b[^|]*\\|\\s*(?:ba|z|s)?sh\\b", "(?i)>\\s*/dev/sd[a-z]",
            // chmod -R 777 of root-ish paths.
            "(?i)\\bchmod\\s+-R\\s+[0-7]{3,4}\\s+/(?![a-z])",
            // macOS disk management can erase the startup disk or any mounted external volume.
            "(?i)\\bdiskutil\\s+(?:eraseDisk|eraseVolume|partitionDisk|zeroDisk|randomDisk)\\b",
            "(?i)\\bdiskutil\\s+apfs\\s+(?:deleteVolume|deleteContainer|eraseVolume)\\b",
            // ---- Windows-native destructive commands ----
            // These become reachable when the bash tool has no Git Bash and falls back to
            // powershell.exe -> cmd.exe /d /c, which executes raw Windows commands. The Unix
            // patterns above catch nothing here, so del/rd/format/diskpart/Remove-Item slipped
            // through every permission mode. Regexes are anchored tightly to avoid false
            // positives on benign commands (git format-patch, gofmt, Get-ChildItem, plain rd).
            //
            // Recursive/forced delete: del|erase|rd|rmdir carrying a /s flag (in any order with
            // /q /f). Matches `del /f /s /q C:\`, `rd /s /q C:\Users`; ignores `rd foo` (no /s).
            "(?i)\\b(?:del|erase|rd|rmdir)\\s+(?:/\\w+\\s+)*/s\\b",
            // Delete aimed directly at a drive root (C:\) or UNC root (\\). Matches `del C:\`,
            // `rmdir /s D:\`; a deeper path like C:\Users\foo is left to the /s rule above.
            "(?i)\\b(?:del|erase|rd|rmdir)\\b(?:\\s+/\\w+)*\\s+(?:[A-Za-z]:\\\\|\\\\\\\\)(?:\\s|$)",
            // Disk format: `format` immediately followed by a drive letter. `[A-Za-z]:` after the
            // whitespace is what excludes `git format-patch`, `gofmt`, `--format json`, etc.
            "(?i)\\bformat\\s+[A-Za-z]:",
            // Disk management verbs.
            "(?i)\\b(?:diskpart|clear-disk|format-volume)\\b",
            // PowerShell recursive delete: Remove-Item ... -Recurse (with or without -Force).
            // The mandatory -Recurse token is what spares a plain `Remove-Item foo.txt`.
            "(?i)\\bremove-item\\b[^\\n]*\\s-recurse\\b");
        List<String> userPatterns = this.cfg.getDangerousPatterns();
        List<String> patterns;
        if (this.cfg.isReplaceDefaultPatterns()) {
            // Explicit opt-out: use ONLY what the operator supplied. Empty list is a valid choice
            // (turns off the denylist entirely) and is left to the operator's judgement.
            patterns = userPatterns == null ? List.of() : userPatterns;
            if (userPatterns == null || userPatterns.isEmpty()) {
                log.warn(
                    "harnex.approval.replace-default-patterns=true with empty dangerous-patterns list — the dangerous-command denylist is DISABLED");
            } else {
                log.warn(
                    "harnex.approval.replace-default-patterns=true — using ONLY operator-supplied patterns ({} entries); hardened defaults are NOT installed",
                    userPatterns.size());
            }
        } else {
            // Additive (default): defaults always present, user patterns appended.
            if (userPatterns == null || userPatterns.isEmpty()) {
                patterns = defaults;
            } else {
                List<String> merged = new ArrayList<>(defaults.size() + userPatterns.size());
                merged.addAll(defaults);
                merged.addAll(userPatterns);
                patterns = merged;
            }
        }
        for (String p : patterns) {
            try {
                dangerous.add(Pattern.compile(p));
            } catch (Exception e) {
                log.warn("Invalid pattern skipped: {}", p);
            }
        }
    }

    /** Focused-test constructor for approval cases that do not need workspace classification. */
    public ApprovalService(AgentConfig cfg, SessionStore sessionStore) {
        this(cfg, sessionStore, null);
    }

    @PostConstruct
    public void init() {
        // Register thread deletion listener via the SessionStore interface — no concrete-type cast.
        sessionStore.onThreadDeleted(this::purgeThread);
        log.info("ApprovalService registered thread deletion listener");
        // B7: every 5 minutes, drop pending entries older than PENDING_TTL_MS or already done.
        cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "approval-cleaner");
            t.setDaemon(true);
            return t;
        });
        cleaner.scheduleWithFixedDelay(this::pruneStale, 1, 1, TimeUnit.MINUTES);
    }

    @PreDestroy
    public void close() {
        if (cleaner != null) {
            cleaner.shutdownNow();
        }
        for (var entry : pending.entrySet()) change(entry.getValue().threadId, () -> {
            synchronized (entry.getValue()) { entry.getValue().future.complete(Decision.DENY); }
            return pending.remove(entry.getKey(), entry.getValue());
        });
    }

    private void pruneStale() {
        long now = System.currentTimeMillis();
        int before = pending.size();
        for (var entry : pending.entrySet()) {
            PendingDecision pd = entry.getValue();
            if (now - pd.createdAt <= PENDING_TTL_MS) continue;
            change(pd.threadId, () -> { synchronized (pd) { pd.future.complete(Decision.DENY); } return pending.remove(entry.getKey(), pd); });
        }
        int after = pending.size();
        if (before != after) {
            log.debug("ApprovalService pruned {} stale pending entries (now {})", before - after, after);
        }
    }

    public Decision decide(ToolCall call, ToolContext ctx) {
        String args = call.getArguments() == null ? "" : call.getArguments();
        // Match dangerous patterns against *parsed* command-bearing fields rather than the raw JSON
        // string. The previous JSON-string match could be evaded by JSON escapes (\u002D for '-')
        // or any quoting/whitespace not literally matched by the regex. We extract common command
        // fields ("command", "cmd", "code", "script", "input") and match each one normalised.
        String haystack = extractCommandHaystack(args);
        for (Pattern p : dangerous) {
            if (p.matcher(haystack).find()) {
                log.warn("DENY dangerous tool call {}: matches {}", call.getName(), p.pattern());
                return Decision.DENY;
            }
        }
        Decision outsideWrite = outsideWriteDecision(call, ctx);
        if (outsideWrite != null) {
            return outsideWrite;
        }
        if (ctx.getOptions() != null && !ctx.getOptions().permissions().allowMutations()) {
            return Decision.ASK; // Tool capability admission applies the narrower ceiling in the executor.
        }
        String tid = ctx.getThread().getId();
        ConcurrentMap<String, Boolean> wl = whitelist.computeIfAbsent(tid, k -> new ConcurrentHashMap<>());
        if (wl.containsKey(call.getName())) {
            return Decision.ALLOW;
        }
        // A child's visible approval is recorded on its root. Reuse only that established grant;
        // dangerous paths and the child's authority ceiling have already been checked above.
        if (ctx.getThread().getSubagentDepth() > 0 && ctx.getRootThreadId() != null) {
            Map<String, Boolean> rootGrants = whitelist.get(ctx.getRootThreadId());
            if (rootGrants != null && rootGrants.containsKey(call.getName())) return Decision.ALLOW;
        }

        // Global cfg.mode override wins over per-thread mode. The previous order checked the
        // thread mode FIRST and only fell through to cfg.mode when thread mode was null/blank;
        // because {@link com.harnex.model.ThreadState#permissionMode} defaults to "ask"
        // (not null) the fallback never triggered, leaving the documented
        // harnex.approval.mode=bypass|strict knob effectively dead for any normal WebUI/TUI
        // session. Now cfg.mode=bypass/strict applies globally; cfg.mode=default (the documented
        // "no global override") defers to the per-thread permissionMode for finer-grained
        // Claude-Code-style control. The dangerous-pattern denylist above is unconditional and
        // applies even under bypass.
        String globalMode = cfg.getMode() == null ? "default" : cfg.getMode().toLowerCase();
        if ("bypass".equals(globalMode)) {
            return Decision.ALLOW;
        }
        if ("strict".equals(globalMode)) {
            return Decision.ASK;
        }

        // Per-thread permission mode (Claude Code / opencode-style) — only consulted when
        // cfg.mode=default (or unset). Defaults to "ask" if neither was supplied.
        String pm = ctx.getThread().getState() == null ? null : ctx.getThread().getState().getPermissionMode();
        if (pm == null || pm.isBlank()) {
            pm = "ask";
        }
        switch (pm.toLowerCase()) {
            case "yolo":
                // All tools auto-allowed (dangerous denylist already applied above).
                return Decision.ALLOW;
            case "accept-edits":
                // Whitelist non-destructive edit tools; everything else still asks.
                if (isAutoAllowedEdit(call.getName())) {
                    return Decision.ALLOW;
                }
                return Decision.ASK;
            case "ask":
            default:
                return Decision.ASK;
        }
    }

    /** True only for policy=ask calls that must carry a one-shot approval token into the file tool. */
    public boolean requiresOutsideWriteApproval(ToolCall call, ToolContext ctx) {
        return outsideWriteDecision(call, ctx) == Decision.ASK;
    }

    /** null means the call has no statically identifiable outside/sensitive write target. */
    private Decision outsideWriteDecision(ToolCall call, ToolContext ctx) {
        PathCheck check = outsideWriteCheck(call, ctx);
        if (check != null && check.decision() == Decision.DENY) log.warn("{}", check.reason());
        return check == null ? null : check.decision();
    }

    /** Preserve the specific reason so a model can correct its path instead of repeating a generic denied call. */
    public String denialReason(ToolCall call, ToolContext ctx) {
        String haystack = extractCommandHaystack(call.getArguments() == null ? "" : call.getArguments());
        if (dangerous.stream().anyMatch(pattern -> pattern.matcher(haystack).find())) {
            return "Denied by approval policy: this command matches a destructive-operation rule. Use a narrower, non-destructive operation.";
        }
        PathCheck check = outsideWriteCheck(call, ctx);
        return check != null && check.decision() == Decision.DENY ? check.reason() : "Denied by approval policy";
    }

    private record PathCheck(Decision decision, String reason) {}

    private PathCheck outsideWriteCheck(ToolCall call, ToolContext ctx) {
        if (workspacePaths == null || call == null || ctx == null || ctx.getThread() == null) return null;
        List<String> candidates = potentialWritePaths(call);
        if (candidates.isEmpty()) return null;
        String threadId = ctx.getThread().getId();
        String outside = null;
        String cwd = null;
        if ("bash".equals(call.getName())) {
            try { cwd = APPROVAL_JSON.readTree(call.getArguments()).path("cwd").asText(null); }
            catch (Exception ignored) { }
        }
        for (String candidate : candidates) {
            Zone zone;
            try {
                zone = "bash".equals(call.getName()) ? workspacePaths.classifyCommandPath(candidate, cwd, threadId)
                    : workspacePaths.classifyInputPath(candidate, threadId);
            } catch (RuntimeException invalid) {
                return new PathCheck(Decision.DENY, "Cannot resolve the tool path '" + candidate + "': " + invalid.getMessage()
                    + ". Use a literal project-relative output path and the cwd parameter; do not splice host paths with shell expressions.");
            }
            if (zone == Zone.RED) {
                return new PathCheck(Decision.DENY, "Write blocked: '" + candidate
                    + "' resolves to a private or sensitive path. Choose a non-private project-relative output path.");
            }
            if (zone == Zone.YELLOW) outside = candidate;
        }
        if (outside == null) return null;
        return switch (workspacePaths.getOutsideWritePolicy()) {
            case "deny" -> new PathCheck(Decision.DENY, "Write outside the workspace is blocked by workspace.outside-write-policy=deny: '"
                + outside + "'. Use a project-relative output path or a temporary file inside the project.");
            case "ask" -> new PathCheck(Decision.ASK, "Writing outside the workspace requires approval: " + outside);
            case "allow" -> null;
            default -> new PathCheck(Decision.DENY, "Invalid outside-write policy");
        };
    }

    private static List<String> potentialWritePaths(ToolCall call) {
        String tool = call.getName() == null ? "" : call.getName();
        if (!List.of("file_write", "file_edit", "multi_edit", "bash").contains(tool)) return List.of();
        try {
            JsonNode root = APPROVAL_JSON.readTree(call.getArguments() == null ? "{}" : call.getArguments());
            if ("file_write".equals(tool) || "file_edit".equals(tool) || "multi_edit".equals(tool)) {
                JsonNode path = root == null ? null : root.get("path");
                return path != null && path.isTextual() && !path.asText().isBlank()
                    ? List.of(path.asText()) : List.of();
            }
            JsonNode command = root == null ? null : root.get("command");
            if (command == null || !command.isTextual()) return List.of();
            return shellWritePathCandidates(command.asText());
        } catch (Exception ignored) {
            return List.of();
        }
    }

    /** Best-effort static shell analysis; dynamic paths still remain subject to ordinary Bash approval/sandboxing. */
    private static List<String> shellWritePathCandidates(String command) {
        if (command == null || command.isBlank() || ShellCommandClassifier.isReadOnlyShellCommand(command)) {
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        List<String> tokens = tokenizeShell(maskPythonMembershipData(command));
        // Wrapper switches are syntax, not filesystem targets (/c is not the root of drive C).
        for (int depth = 0; depth < 4 && !tokens.isEmpty(); depth++) {
            String executable = tokens.get(0).replace('\\', '/').replaceAll(".*/", "").toLowerCase(Locale.ROOT);
            boolean cmd = Set.of("cmd", "cmd.exe").contains(executable);
            boolean powershell = Set.of("powershell", "powershell.exe", "pwsh", "pwsh.exe").contains(executable);
            if (!cmd && !powershell) break;
            int payload = -1;
            for (int index = 1; index < tokens.size(); index++) {
                String flag = tokens.get(index).toLowerCase(Locale.ROOT);
                if (cmd && Set.of("/c", "/k").contains(flag)
                    || powershell && Set.of("-command", "-c").contains(flag)) { payload = index + 1; break; }
            }
            if (payload < 0 || payload >= tokens.size()) break;
            tokens = tokens.size() == payload + 1 ? tokenizeShell(tokens.get(payload))
                : new ArrayList<>(tokens.subList(payload, tokens.size()));
        }
        for (int index = 0; index < tokens.size(); index++) {
            String raw = tokens.get(index);
            if (index == 0 && !raw.matches("^\\d*>.*")) continue; // executable paths are not write targets
            String token = raw;
            boolean redirect = token.matches("^\\d*>{1,2}.+");
            boolean outputOption = token.matches("^--(?:output|screenshot|output-dir)=.+");
            token = token.replaceFirst("^\\d*>{1,2}", "");
            int equals = token.indexOf('=');
            if (equals > 0 && equals + 1 < token.length()) token = token.substring(equals + 1);
            token = token.replaceFirst("[|;&]+$", "");
            if (token.isBlank() || token.equals("/dev/null") || token.equalsIgnoreCase("NUL")) continue;
            // Variable-only targets retain ordinary shell approval. Do not treat $env:FOO as a literal Windows filename.
            if (token.startsWith("$") || token.startsWith("%")) continue;
            String previous = index == 0 ? "" : tokens.get(index - 1).toLowerCase(Locale.ROOT);
            boolean outputArgument = previous.matches("^\\d*>{1,2}$")
                || Set.of("-o", "--output", "--screenshot", "-outfile", "-filepath", "-literalpath", "-path").contains(previous);
            if (redirect || outputOption || outputArgument || token.startsWith("/") || token.startsWith("~/") || token.startsWith("../")
                || token.startsWith("..\\") || token.matches("^[A-Za-z]:[\\\\/].*")) {
                paths.add(token);
            }
        }
        return paths.stream().distinct().toList();
    }

    /** A quoted Python here-document is program input. Literal operands of `in`/`not in` are
     * data comparisons, not shell output paths (for example `'/' in path`). Keep the rest of
     * the script, header and subsequent shell commands available to the conservative path check.
     * The unconditional dangerous-command check above always receives the original source. */
    private static String maskPythonMembershipData(String command) {
        Pattern headers = Pattern.compile("(?m)^[\\t ]*(?:python[0-9.]*(?:\\.exe)?|py(?:\\.exe)?)\\b[^\\r\\n]*?<<(-?)[\\t ]*(['\"])([A-Za-z_][A-Za-z0-9_]*)\\2[^\\r\\n]*\\R");
        Pattern operands = Pattern.compile("(?:'[^'\\r\\n]*'|\"[^\"\\r\\n]*\")(?=\\s+(?:not\\s+)?in\\b)");
        var matches = headers.matcher(command);
        StringBuilder result = new StringBuilder();
        int copied = 0;
        while (matches.find()) {
            if (matches.start() < copied) continue;
            var end = Pattern.compile("(?m)^" + (matches.group(1).isEmpty() ? "" : "\\t*")
                + Pattern.quote(matches.group(3)) + "\\r?$").matcher(command);
            if (!end.find(matches.end())) continue;
            result.append(command, copied, matches.end());
            result.append(operands.matcher(command.substring(matches.end(), end.start())).replaceAll("'comparison-data'"));
            result.append(command, end.start(), end.end());
            copied = end.end();
        }
        return copied == 0 ? command : result.append(command, copied, command.length()).toString();
    }

    private static List<String> tokenizeShell(String command) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            if (quote != 0) {
                // In double quotes only these characters are backslash-escaped. Preserve
                // other backslashes (including Windows paths); single quotes are literal.
                if (quote == '"' && ch == '\\' && i + 1 < command.length()
                    && "\"\\$`\n".indexOf(command.charAt(i + 1)) >= 0) {
                    char escaped = command.charAt(++i);
                    if (escaped != '\n') current.append(escaped);
                } else if (ch == quote) {
                    quote = 0;
                } else {
                    current.append(ch);
                }
            } else if (ch == '\'' || ch == '"') {
                quote = ch;
            } else if (Character.isWhitespace(ch)) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(ch);
            }
        }
        if (!current.isEmpty()) tokens.add(current.toString());
        return tokens;
    }

    /** Tools considered safe to auto-allow under {@code accept-edits} mode. Bash / web_fetch / delegate excluded. */
    private static boolean isAutoAllowedEdit(String toolName) {
        if (toolName == null)
            return false;
        return switch (toolName) {
            case "file_read", "file_write", "file_edit", "multi_edit", "glob", "grep", "memory_list", "memory_remember",
                "memory_forget", "skill_list", "skill_read", "session_search", "session_read", "session_fact", "todo_read", "todo_write" -> true;
            default -> false;
        };
    }

    /**
     * Frontend calls this to respond to ASK prompts.
     *
     * @return {@code true} if a waiter was found and the decision was applied; {@code false} if no pending approval
     *         exists for {@code callId} (caller can surface a 404/410 to the UI instead of silently no-op'ing). The
     *         previous void return signature led the controller to always 200-OK even when the agent had already moved
     *         on (timeout fired, tool was cancelled, server restarted), so the user thought their click had landed.
     */
    public boolean recordDecision(String threadId, String callId, String toolName, String choice) {
        return change(threadId, () -> recordDecisionInside(threadId, callId, toolName, choice));
    }
    private boolean recordDecisionInside(String threadId, String callId, String toolName, String choice) {
        if (callId == null) {
            return false;
        }
        String c = choice == null ? "" : choice.toLowerCase();
        Decision d;
        // The "whitelist" choice both ALLOWs the current call AND records a per-thread
        // auto-approval for future calls of the same tool. Defer the whitelist write until
        // AFTER we've confirmed the pending entry exists and our future.complete() actually
        // wins — otherwise an expired/forged callId would return 410 to the UI but still
        // mutate the auth state, silently broadening the agent's permissions for the rest of
        // the conversation. ("Interface fails, permissions change anyway" is the worst of
        // both worlds.)
        boolean wantsWhitelist;
        switch (c) {
            case "allow" -> {
                d = Decision.ALLOW;
                wantsWhitelist = false;
            }
            case "whitelist" -> {
                d = Decision.ALLOW;
                wantsWhitelist = true;
            }
            default -> {
                d = Decision.DENY;
                wantsWhitelist = false;
            }
        }
        // Only complete an EXISTING waiter \u2014 do NOT create a new PendingDecision here. Pre-creating
        // one would let stale frontend clicks (after the agent's pollAndWait already timed out and
        // the entry was pruned) re-create a phantom entry that never gets consumed.
        PendingDecision pd = pending.get(pendingKey(threadId, callId));
        if (pd == null || pd.future.isDone()) {
            return false;
        }
        synchronized (pd) {
            if (pd.future.isDone()) {
                return false;
            }
            if (wantsWhitelist && !pd.outsideWrite && threadId != null) {
                // Write the whitelist before completing the future. CompletableFuture wakes pollAndWait callers
                // immediately from complete(); if we complete first, the tool worker can re-enter decide() for the next
                // call before this thread records the whitelist and observe ASK.
                String effectiveTool = pd.toolName != null && !pd.toolName.isBlank() ? pd.toolName : toolName;
                if (effectiveTool != null && !effectiveTool.isBlank()) {
                    whitelist.computeIfAbsent(threadId, k -> new ConcurrentHashMap<>()).put(effectiveTool, true);
                }
            }
            return pd.future.complete(d);
        }
    }

    public Decision poll(String threadId, String callId) {
        PendingDecision pd = pending.get(pendingKey(threadId, callId));
        return pd != null && pd.future.isDone() ? pd.future.getNow(null) : null;
    }

    /**
     * Pre-register a pending approval slot BEFORE emitting the {@code approval_required} event.
     *
     * <p>
     * This is required because some event consumers (notably the CLI / TUI renderer) handle {@code approval_required}
     * <em>synchronously</em> on the emitting thread — they prompt the user and call {@link #recordDecision}
     * <em>before</em> the executor thread reaches {@link #pollAndWait}. Without a pre-registered slot,
     * {@code recordDecision} would return {@code false} (no waiter yet), the subsequent {@code pollAndWait} would
     * create a fresh slot that nobody ever completes, and the call would hang for the full approval timeout before
     * being denied. WebUI doesn't observe this because user clicks arrive asynchronously over HTTP — but the underlying
     * race exists either way; pre-registering closes it.
     *
     * <p>
     * Idempotent: if a slot already exists for {@code (threadId, callId)} it is reused unchanged. Safe to call from any
     * thread.
     */
    public void registerPending(String threadId, String callId, String toolName) {
        registerPending(threadId, callId, toolName, null);
    }

    /** Variant that also captures the raw tool arguments for pending-list rendering. */
    public void registerPending(String threadId, String callId, String toolName, String arguments) {
        registerPending(threadId, callId, toolName, arguments, false);
    }

    /** Variant marking an outside-write decision as strictly one-shot (never added to the tool whitelist). */
    public void registerPending(String threadId, String callId, String toolName, String arguments,
        boolean outsideWrite) {
        registerPending(threadId, callId, toolName, arguments, outsideWrite, null, null);
    }

    public void registerPending(String threadId, String callId, String toolName, String arguments,
        boolean outsideWrite, String sourceCallId, String subagentThreadId) {
        if (callId == null) {
            return;
        }
        change(threadId, () -> pending.computeIfAbsent(pendingKey(threadId, callId),
            kk -> new PendingDecision(threadId, callId, toolName, arguments, outsideWrite, sourceCallId, subagentThreadId)));
    }

    /** Snapshot of all unresolved approvals (optionally filtered by thread), oldest first. */
    public List<ApprovalPendingView> listPending(String threadIdOrNull) {
        return pending.values().stream().filter(pd -> !pd.future.isDone())
            .filter(pd -> threadIdOrNull == null || threadIdOrNull.equals(pd.threadId))
            .sorted(Comparator.comparingLong(pd -> pd.createdAt))
            .map(pd -> new ApprovalPendingView(pd.threadId, pd.callId, pd.toolName, pd.arguments, pd.outsideWrite,
                pd.createdAt, pd.sourceCallId, pd.subagentThreadId))
            .toList();
    }

    /**
     * Block until a decision is recorded for the given (threadId, callId), or timeout. Returns DENY on timeout /
     * interruption. Race-safe: a decision submitted at any moment before timeout is always observed. The
     * {@code toolName} is captured server-side so {@link #recordDecision} can derive the effective tool to whitelist
     * without trusting the request body.
     */
    public boolean requiresExplicitDecision(ToolCall call, ToolContext context) {
        return "strict".equalsIgnoreCase(cfg.getMode()) || requiresOutsideWriteApproval(call, context);
    }

    public Decision pollAndWait(String threadId, String callId, String toolName, long timeoutMs) {
        return pollAndWait(threadId, callId, toolName, timeoutMs, () -> false);
    }

    public Decision pollAndWait(String threadId, String callId, String toolName, long timeoutMs,
        BooleanSupplier stopped) {
        String k = pendingKey(threadId, callId);
        PendingDecision pd = pending.get(k);
        if (pd == null) return Decision.DENY;
        try {
            return InteractionBroker.await(pd.future, timeoutMs, stopped);
        } catch (TimeoutException te) {
            // Signal timeout to any later recorder by completing with DENY. Synchronize on the PendingDecision so this
            // cannot race with recordDecision("whitelist"): the recorder writes whitelist before completing ALLOW, so
            // timeout completion must be mutually exclusive or a late click could return false/410 while still
            // widening permissions.
            return endWait(threadId, pd, Decision.ASK);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return endWait(threadId, pd, Decision.DENY);
        } catch (Exception e) {
            return Decision.DENY;
        } finally {
            change(threadId, () -> { synchronized (pd) { pd.future.complete(Decision.DENY); } pending.remove(k, pd); return null; });
        }
    }
    private Decision endWait(String threadId, PendingDecision pd, Decision whenMissing) {
        return change(threadId, () -> { synchronized (pd) {
            return pd.future.complete(Decision.DENY) ? whenMissing : pd.future.getNow(Decision.DENY);
        }});
    }

    /** Back-compat overload retained for callers / tests that don't supply threadId or toolName. */
    public Decision pollAndWait(String callId, long timeoutMs) {
        return pollAndWait(null, callId, null, timeoutMs);
    }

    /** Drop any per-thread whitelist entries when the thread is deleted. */
    public void purgeThread(String threadId) {
        whitelist.remove(threadId);
    }

    /** Wake outstanding approval waits as DENY without altering the thread's established whitelist. */
    public int supersedePendingForThread(String threadId) {
        return change(threadId, () -> supersedeInside(threadId));
    }
    private int supersedeInside(String threadId) {
        if (threadId == null) return 0;
        int count = 0;
        for (var entry : pending.entrySet()) {
            PendingDecision decision = entry.getValue();
            if (!threadId.equals(decision.threadId)) continue;
            synchronized (decision) {
                if (decision.future.complete(Decision.DENY)) count++;
            }
            pending.remove(entry.getKey(), decision);
        }
        return count;
    }

    /** Jackson reused for parsing tool-call JSON arguments — never null. */
    private static final ObjectMapper APPROVAL_JSON = new ObjectMapper();
    /** Field names that, when present in tool arguments, contain user/agent commands worth scanning. */
    private static final List<String> COMMAND_FIELDS = List.of("command", "cmd", "code", "script", "input", "args");

    /**
     * Build the haystack for {@link #dangerous} patterns by parsing the tool-call JSON arguments and concatenating any
     * string-valued command-bearing fields. Recurses into nested objects/arrays because MCP and custom tools commonly
     * wrap the real command one or more levels deep (e.g. {@code {"payload":{"command":"rm -rf /home"}}} from an MCP
     * transport adapter, or {@code {"steps":[{"cmd":"..."}]}} from a workflow tool). The previous top-level-only scan
     * meant such a payload bypassed parsed-mode matching and only had the raw JSON to fall back on — in which case a
     * JSON-escaped char (e.g. {@code \u002d} for {@code -}) would silently defeat any regex anchored on the literal
     * character. Falls back to the raw JSON if parsing fails. Depth + size caps bound the cost on pathological inputs
     * without crippling realistic deeply-nested workflow payloads.
     */
    private static final int MAX_HAYSTACK_DEPTH = 8;
    private static final int MAX_HAYSTACK_CHARS = 32_768;

    private static String extractCommandHaystack(String json) {
        if (json == null || json.isEmpty()) {
            return "";
        }
        try {
            JsonNode root = APPROVAL_JSON.readTree(json);
            if (root == null) {
                return json;
            }
            StringBuilder sb = new StringBuilder();
            collectCommandValues(root, sb, 0);
            // Always include the raw JSON too so patterns that depend on field-name context
            // (none of the defaults do, but custom rules might) still match.
            sb.append(json);
            String base = sb.toString();
            // Peel any nested interpreter wrappers (sh|bash|dash|zsh -c "...") so a dangerous
            // command hidden inside `sh -c "rm -rf /home"` is surfaced as its inner text and
            // matched by the same denylist. Bounded internally (depth/char/match caps).
            String nested = ShellCommandClassifier.collectNestedShellCommands(base);
            if (nested != null && !nested.isEmpty()) {
                return base + "\n" + nested;
            }
            return base;
        } catch (Exception e) {
            return json;
        }
    }

    private static void collectCommandValues(JsonNode node, StringBuilder sb, int depth) {
        if (node == null || depth > MAX_HAYSTACK_DEPTH || sb.length() > MAX_HAYSTACK_CHARS) {
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String key = e.getKey();
                JsonNode v = e.getValue();
                if (key != null && COMMAND_FIELDS.contains(key.toLowerCase())) {
                    if (v.isTextual()) {
                        sb.append(v.asText()).append('\n');
                    } else if (v.isArray()) {
                        for (JsonNode el : v) {
                            if (el.isTextual()) {
                                sb.append(el.asText()).append(' ');
                            }
                        }
                        sb.append('\n');
                    }
                }
                // Recurse regardless of key name so a command-bearing field nested inside an
                // arbitrarily-named wrapper (payload / params / data / input / step / etc.)
                // is still scanned.
                collectCommandValues(v, sb, depth + 1);
            }
        } else if (node.isArray()) {
            for (JsonNode el : node) {
                collectCommandValues(el, sb, depth + 1);
            }
        }
    }
}
