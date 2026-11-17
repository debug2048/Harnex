package com.harnex.process;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies shell commands as read-only (safe) or potentially mutating. Extracted from
 * {@code AgentLoop} to allow reuse in plan-mode gating and restore-point logic.
 */
public final class ShellCommandClassifier {

    private ShellCommandClassifier() {
    }

    /**
     * Entry point: returns {@code true} if the given command value represents a read-only shell
     * command (e.g. ls, cat, git log) that does not mutate the filesystem or environment.
     */
    public static boolean isReadOnlyShellCommand(Object commandValue) {
        String command = commandValue == null ? "" : String.valueOf(commandValue).trim();
        if (command.isBlank()) {
            return false;
        }
        String normalized = command.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        if (normalized.matches(".*\\b(out-file|set-content|add-content|new-item|remove-item|move-item|copy-item)\\b.*")
            || normalized.matches(".*(^|\\s)-(delete|exec|execdir|ok|okdir|fprint|fprintf|fls)\\b.*")
            || normalized.matches(".*\\s--output(?:=|\\s).*")) {
            return false;
        }
        // Unwrap executable command strings before masking ordinary quoted arguments.
        String ps = unwrapPowerShellCommand(normalized);
        if (ps != null) {
            return isReadOnlyPowerShellCommand(ps);
        }
        String cmd = unwrapCmdCommand(normalized);
        if (cmd != null) {
            return isReadOnlyShellCommand(cmd);
        }
        String syntax = unquotedShellSyntax(normalized);
        if (syntax == null) {
            return false;
        }
        String withoutNullRedirections = stripNullRedirections(syntax);
        if (withoutNullRedirections.contains(">") || withoutNullRedirections.contains("| tee ")) {
            return false;
        }
        Matcher separator = Pattern.compile("&&|;|\\|\\||\\|").matcher(syntax);
        int start = 0;
        while (separator.find()) {
            if (!isReadOnlyShellSegment(normalized.substring(start, separator.start()).trim())) {
                return false;
            }
            start = separator.end();
        }
        return isReadOnlyShellSegment(normalized.substring(start).trim());
    }

    /**
     * Masks quoted/escaped characters with spaces, preserving offsets for segment extraction.
     * Returns null for incomplete quoting or executable expansions we cannot classify safely.
     * Keep the original arguments for command-specific checks (e.g. awk's system()).
     */
    private static String unquotedShellSyntax(String command) {
        StringBuilder syntax = new StringBuilder(command.length());
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            if (ch == '\\' && quote != '\'') {
                if (i + 1 >= command.length()) return null;
                char next = command.charAt(i + 1);
                if (quote == 0 || "\"\\$`\n".indexOf(next) >= 0) {
                    syntax.append("  ");
                    i++;
                    continue;
                }
            }
            if (quote != '\'' && (ch == '`'
                || (ch == '$' && i + 1 < command.length() && command.charAt(i + 1) == '(')
                || (quote == 0 && (ch == '<' || ch == '>')
                    && i + 1 < command.length() && command.charAt(i + 1) == '('))) {
                return null;
            }
            if (quote != 0) {
                if (ch == quote) quote = 0;
                syntax.append(' ');
            } else if (ch == '\'' || ch == '"') {
                quote = ch;
                syntax.append(' ');
            } else {
                syntax.append(ch);
            }
        }
        return quote == 0 ? syntax.toString() : null;
    }

    public static boolean isReadOnlyShellSegment(String segment) {
        if (segment.isBlank()) {
            return true;
        }
        String s = segment.trim();
        if (s.matches("^(done|fi|then|else|do)$")) {
            return true;
        }
        if (s.matches("^for\\s+[a-z_][a-z0-9_]*\\s+in\\s+.+$") && !s.contains("$(") && !s.contains("`")) {
            return true;
        }
        for (String prefix : new String[] { "do ", "then ", "else " }) {
            if (s.startsWith(prefix)) {
                return isReadOnlyShellSegment(s.substring(prefix.length()));
            }
        }
        if (s.startsWith("if ")) {
            return isReadOnlyShellSegment(s.substring(3));
        }
        if (s.startsWith("cd ") || "cd".equals(s)) {
            return true;
        }
        String ps = unwrapPowerShellCommand(s);
        if (ps != null) {
            return isReadOnlyPowerShellCommand(ps);
        }
        String cmd = unwrapCmdCommand(s);
        if (cmd != null) {
            return isReadOnlyShellCommand(cmd);
        }
        if (isReadOnlyPowerShellSegment(s)) {
            return true;
        }
        if (s.startsWith("git ")) {
            return isReadOnlyGitCommand(s);
        }
        // With no arguments hostname only queries; a name operand or -F can change the host.
        if (stripNullRedirections(s).trim().equals("hostname")) {
            return true;
        }
        return s.matches("^(pwd|ls|dir|cat|type|head|tail|wc|rg|grep|find|tree|where|which|cut|uniq|tr|jq|yq|file|stat|du|df|echo|printf|printenv)(\\s+.*)?$")
            || (s.matches("^awk(\\s+.*)?$") && !s.matches(".*\\bsystem\\s*\\(.*")
                && !s.contains(">") && !s.contains("|"))
            || (s.matches("^sort(\\s+.*)?$") && !s.matches(".*\\s-o(?:\\s|\\S).*"))
            || s.matches("^env(?:\\s+(?:-0|--null))*$")
            || s.matches("^command\\s+-v(\\s+.*)?$")
            // Conservatively exclude in-place edits and script write/execute commands or flags.
            || (s.matches("^sed(\\s+.*)?$") && !s.matches(".*\\s-i\\b.*") && !s.matches(".*\\s--in-place\\b.*")
                && !s.matches(".*(?<![-\\w])\\d*[we]\\b.*"))
            || s.matches("^(mvn|gradle|npm|pnpm|yarn)\\s+(test|check|verify)(\\s+.*)?$")
            || s.matches("^(pytest|go test|cargo test)(\\s+.*)?$");
    }

    public static String unwrapPowerShellCommand(String segment) {
        String s = segment.trim();
        if (!s.matches("^(powershell(?:\\.exe)?|pwsh(?:\\.exe)?)(\\s+.*)?$")) {
            return null;
        }
        Matcher m = Pattern.compile("(?i)(?:^|\\s)-(?:command|c)\\s+(.+)$").matcher(s);
        if (!m.find()) {
            return null;
        }
        return stripShellQuotes(m.group(1).trim());
    }

    public static String unwrapCmdCommand(String segment) {
        String s = segment.trim();
        if (!s.matches("^cmd(?:\\.exe)?\\s+/[cs]\\s+.*$")) {
            return null;
        }
        return stripShellQuotes(s.replaceFirst("^cmd(?:\\.exe)?\\s+/[cs]\\s+", "").trim());
    }

    private static boolean isReadOnlyPowerShellCommand(String command) {
        String normalized = stripShellQuotes(String.valueOf(command == null ? "" : command).trim())
            .replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return false;
        }
        if (stripNullRedirections(normalized).contains(">") || normalized.matches(
            ".*\\b(set-content|add-content|out-file|new-item|remove-item|move-item|copy-item|rename-item|clear-content|start-process|invoke-webrequest|iwr|curl|wget)\\b.*")) {
            return false;
        }
        String[] parts = normalized.split("\\s*(?:;|&&|\\|\\||\\|)\\s*");
        for (String part : parts) {
            String p = part.trim();
            if (p.isBlank()) {
                continue;
            }
            if (!isReadOnlyPowerShellSegment(p)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isReadOnlyPowerShellSegment(String segment) {
        return segment.matches(
            "^(get-childitem|gci|dir|ls|get-content|gc|cat|type|select-string|sls|select-object|select|where-object|where|sort-object|sort|format-table|ft|format-list|fl|get-location|pwd|resolve-path|test-path|get-item|gi|get-command|measure-object|out-string|get-process|gps|get-service|get-date|get-variable|get-member|compare-object|group-object|convertfrom-json|convertto-json|join-path|split-path)(\\s+.*)?$");
    }

    private static boolean isReadOnlyGitCommand(String command) {
        if (command.matches(
            "^git\\s+(status|diff|log|show|rev-parse|ls-files|ls-tree|grep|blame|cat-file|check-ignore|describe|name-rev|shortlog)(\\s+.*)?$")) {
            return true;
        }
        if (command.matches("^git\\s+branch(?:\\s+(?:--list|-l|--show-current|-a|--all|-r|--remotes|-v|-vv))*$")) {
            return true;
        }
        if (command.matches("^git\\s+remote(?:\\s+(?:-v|show(?:\\s+\\S+)?|get-url(?:\\s+--all)?\\s+\\S+))?$")) {
            return true;
        }
        return command.matches("^git\\s+config\\s+(?:--list|-l|--get|--get-all|--get-regexp)(?:\\s+.*)?$");
    }

    private static String stripNullRedirections(String command) {
        return command
            .replaceAll("(?i)(?:\\d?>+\\s*/dev/null|\\d?>+\\s*\\$null)", "")
            .replaceAll("\\d?>&\\d+", "");
    }

    private static String stripShellQuotes(String s) {
        String out = s == null ? "" : s.trim();
        while (out.length() >= 2) {
            char first = out.charAt(0);
            char last = out.charAt(out.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                out = out.substring(1, out.length() - 1).trim();
            } else {
                break;
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- nested shell -c unwrapping
    //
    // Security helper (used by the approval denylist): a dangerous command can be hidden from a
    // literal-string regex by wrapping it in an interpreter, e.g. `sh -c "rm -rf /home"` or
    // `bash -c "sh -c 'rm -rf /etc'"`. The denylist matches an extracted "haystack" of command
    // strings; without unwrapping, only the OUTER `bash -c "..."` text is present and the inner
    // destructive command may slip through. {@link #collectNestedShellCommands} peels the inner
    // command string(s) out of `sh|bash|dash|zsh|ash|ksh -c/-lc/-ic <arg>` invocations so the
    // existing patterns match the inner command directly.

    /** Matches an interpreter invocation with a command-string flag and captures the inner arg. */
    private static final Pattern NESTED_SHELL_DASH_C = Pattern.compile(
        "(?i)(?:^|[\\s;&|(=])(?:/\\S*/)?(?:ba|da|z|a|k)?sh\\s+-[a-z]*c[a-z]*\\s+(?:\"([^\"]*)\"|'([^']*)'|(\\S[^\\n]*))");
    /**
     * Windows interpreter wrappers: {@code powershell|pwsh [-Command|-c] <inner>} and
     * {@code cmd [/c|/k] <inner>}. Mirrors {@link #NESTED_SHELL_DASH_C}'s three capture-group
     * shape (double-quoted / single-quoted / unquoted) so the same extraction logic applies.
     * Windows-native destructive commands (del /s, rd /s, format X:, Remove-Item -Recurse) only
     * really run when the bash tool has no Git Bash and falls back to powershell.exe -> cmd.exe
     * /d /c; unwrapping these closes the same denylist-evasion hole the Unix unwrapper closes for
     * {@code sh -c}. {@code -c[a-z]*} tolerates PowerShell's prefix abbreviations (-Comm, -Command);
     * a leading command-name boundary {@code (?:^|[\s;&|(=])} and non-backtracking classes keep it
     * ReDoS-safe. Group 1 = double-quoted inner, group 2 = single-quoted, group 3 = unquoted.
     */
    private static final Pattern NESTED_WIN_SHELL = Pattern.compile(
        "(?i)(?:^|[\\s;&|(=])(?:(?:powershell|pwsh)(?:\\.exe)?\\s+-c[a-z]*|cmd(?:\\.exe)?\\s+/[ck])\\s+(?:\"([^\"]*)\"|'([^']*)'|(\\S[^\\n]*))");
    /** All interpreter-wrapper patterns iterated per layer by {@link #collectNestedShellCommands}. */
    private static final Pattern[] NESTED_SHELL_PATTERNS = { NESTED_SHELL_DASH_C, NESTED_WIN_SHELL };
    /** Max nesting layers to peel — bounds cost and prevents runaway loops on crafted input. */
    private static final int MAX_UNWRAP_DEPTH = 4;
    /** Upper bound on the total unwrapped text we accumulate (bounds memory / ReDoS surface). */
    private static final int MAX_UNWRAP_CHARS = 16_384;
    /** Cap on matches pulled from a single string per layer, so one line can't fan out unbounded. */
    private static final int MAX_MATCHES_PER_LEVEL = 64;

    /**
     * Recursively unwrap {@code sh|bash|dash|zsh|ash|ksh -c/-lc/-ic "<inner>"} invocations found in
     * {@code haystack} and return the collected inner command strings (one per line). Handles
     * single-quoted, double-quoted and unquoted arguments, and multi-layer nesting such as
     * {@code bash -c "sh -c 'rm -rf /etc'"}. Bounded by {@link #MAX_UNWRAP_DEPTH},
     * {@link #MAX_UNWRAP_CHARS} and {@link #MAX_MATCHES_PER_LEVEL} to stay safe on hostile input.
     * Returns an empty string when there is nothing to unwrap (never {@code null}).
     */
    public static String collectNestedShellCommands(String haystack) {
        if (haystack == null || haystack.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        Deque<String> current = new ArrayDeque<>();
        current.add(haystack);
        int depth = 0;
        while (!current.isEmpty() && depth < MAX_UNWRAP_DEPTH && out.length() < MAX_UNWRAP_CHARS) {
            Deque<String> next = new ArrayDeque<>();
            for (String s : current) {
                if (s == null || s.isEmpty() || out.length() >= MAX_UNWRAP_CHARS) {
                    continue;
                }
                // Per-string budget shared across all wrapper patterns so one line can't fan out
                // beyond MAX_MATCHES_PER_LEVEL regardless of how many interpreter forms it mixes.
                int found = 0;
                for (Pattern pat : NESTED_SHELL_PATTERNS) {
                    Matcher m = pat.matcher(s);
                    while (m.find() && found < MAX_MATCHES_PER_LEVEL && out.length() < MAX_UNWRAP_CHARS) {
                        String inner = m.group(1) != null ? m.group(1)
                            : (m.group(2) != null ? m.group(2) : m.group(3));
                        if (inner == null) {
                            continue;
                        }
                        inner = inner.trim();
                        if (inner.isEmpty()) {
                            continue;
                        }
                        out.append(inner).append('\n');
                        next.add(inner);
                        found++;
                    }
                }
            }
            current = next;
            depth++;
        }
        return out.toString();
    }
}
