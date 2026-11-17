package com.harnex.tool.budget;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.safety.SecretRedactor;
import com.harnex.tool.ToolContext;
import com.harnex.tool.ToolResult;
import com.harnex.tool.builtin.support.TextEditCore;
import com.harnex.util.Ids;

import lombok.extern.slf4j.Slf4j;

/**
 * Budget enforcer (inspired by Hermes Agent tools/budget_config + tool_result_storage).
 *
 * - Per-tool char limit: if exceeded, truncate + persist full content to disk, replace with preview + ref. - Per-turn
 * aggregate limit: if exceeded, further compress remaining large results.
 */
@Slf4j
@Component
@ProjectRuntime
public class BudgetEnforcer {

    private final AgentConfig.Budget cfg;
    private final Path persistRoot;
    /**
     * Same flags as {@link com.harnex.middleware.SensitiveDataMiddleware}. Required because BudgetEnforcer runs INSIDE
     * {@link com.harnex.tool.ParallelToolExecutor#run} and {@link com.harnex.tool.ParallelToolExecutor#executeBatch},
     * both of which complete BEFORE the {@code afterTools} chain (and therefore before SensitiveDataMiddleware) gets a
     * chance to scrub. Without this, an oversize tool result was persisted to
     * {@code <dataDir>/agent/tool-results/<thread>/<sha>.txt} with raw secrets even when the in-memory preview that re-entered the
     * LLM context was later redacted — and that file is reachable via {@code GET /api/tools/result/{tid}/{ref}},
     * defeating the redaction layer entirely.
     */
    private final boolean redactSecrets;
    private final boolean redactPii;
    /**
     * Real-token counter. Used only when {@code perToolTokens > 0} or {@code perTurnTokens > 0}; otherwise this
     * enforcer remains pure-chars and the counter is never invoked, so deployments that don't enable the token cap pay
     * zero cost for the jtokkit dependency at runtime.
     */
    private final TokenCounter tokens;

    @Autowired
    public BudgetEnforcer(AgentConfig cfg, TokenCounter tokens,
        @Value("${harnex.safety.redact-secrets-in-tool-results:true}") boolean redactSecrets,
        @Value("${harnex.safety.redact-pii-in-tool-results:false}") boolean redactPii) {
        this.cfg = cfg.getBudget();
        this.redactSecrets = redactSecrets;
        this.redactPii = redactPii;
        this.tokens = tokens;
        this.persistRoot = Paths.get(cfg.getSharedDataDir(), "agent/tool-results");
        try {
            Files.createDirectories(persistRoot);
        } catch (IOException e) {
            log.warn("Cannot create tool result directory: {}", e.getMessage());
        }
    }

    /**
     * Backwards-compatible constructor for unit tests and any caller that wired the pre-1.1 (chars-only) signature.
     * Builds a default {@link TokenCounter}; equivalent at runtime to the new constructor when {@code perToolTokens} /
     * {@code perTurnTokens} are both 0 (the shipped defaults), which keeps existing behaviour bit-identical.
     */
    public BudgetEnforcer(AgentConfig cfg, boolean redactSecrets, boolean redactPii) {
        this(cfg, new TokenCounter(), redactSecrets, redactPii);
    }

    /**
     * Apply credential / PII redaction so the on-disk persisted blob and the in-memory preview are derived from the
     * SAME scrubbed source. Hashing the redacted content also keeps the {@code persistRef} stable: two oversize tool
     * results that differ only in their secret material will collide on disk by design — exactly what we want for
     * compliance audits.
     */
    private String scrub(String c) {
        if (c == null || c.isEmpty())
            return c;
        if (!redactSecrets && !redactPii)
            return c;
        return SecretRedactor.redact(c, redactPii);
    }

    /**
     * Whether any redaction pass actually runs inside {@link #scrub(String)}. When both flags are off SecretRedactor is
     * never invoked, so {@link ToolResult#setScrubbed(boolean)} must NOT be set — leaving it {@code false} keeps the
     * downstream {@link com.harnex.middleware.SensitiveDataMiddleware} / AgentLoop fallback intact (they are equally
     * no-ops under the same flags, so nothing leaks and nothing is double-scanned).
     */
    private boolean redactionActive() {
        return redactSecrets || redactPii;
    }

    public void enforcePerTool(ToolResult r, ToolContext ctx) {
        if (r == null || r.getContent() == null) {
            return;
        }
        // 1. Scrub the FULL content FIRST — BEFORE any truncation — so the UI preview, the LLM
        // version AND the on-disk blob are all derived from the SAME redacted source. Truncating
        // before redaction could leave a secret straddling a preview boundary or in a persisted
        // blob reachable via GET /api/tools/result/{tid}/{ref}, defeating the redaction layer.
        String c = scrub(r.getContent());
        if (!c.equals(r.getContent())) {
            r.setContent(c);
        }
        // Mark the whole result as scrubbed as soon as the FULL content has passed through
        // SecretRedactor: every branch below (fast-path full content, null-thread preview,
        // persisted preview + llmContent) derives both the UI and the LLM view from this same
        // scrubbed `c`, so the flag holds for the entire result. AgentLoop reads it to skip the
        // idempotent re-scan. Guarded by redactionActive() so the flag is NEVER set when no
        // redaction actually ran (both flags off) — the bypass fallback then stays in force.
        if (redactionActive()) {
            r.setScrubbed(true);
        }
        // Two independent caps: the small UI-preview cap and the (deliberately larger) LLM cap.
        int effectiveChars = effectivePerToolChars(c, ctx);
        int effectiveLlmChars = effectivePerToolCharsForLlm(c, ctx);

        // Fast path: within the small UI cap → nothing to truncate for EITHER audience. Content
        // stays the full scrubbed text; llmContent stays null so AgentLoop falls back to content
        // (which is complete here). Keeps the common case zero-copy and byte-identical to before.
        if (c.length() <= effectiveChars) {
            return;
        }
        // Preview char budget: never exceed the per-tool cap (so previewChars=2000 with a
        // perToolTokens=500 cap yielding ~2000 chars char-equivalent doesn't double-budget).
        int previewChars = Math.min(cfg.getPreviewChars(), effectiveChars);
        // C6 (P2): a sub-agent invocation that hasn't been attached to a thread (rare; only the
        // direct /api/delegate test path) lands here with ctx.getThread() == null. NPE here
        // would propagate out of the tool executor and abort the whole turn instead of just
        // skipping the persist optimisation. Keep the in-memory truncation behaviour but drop
        // the on-disk persistence (no thread id => no per-thread directory).
        if (ctx.getThread() == null) {
            String preview = safePreview(c, previewChars);
            r.setContent(preview + "\n\n…[truncated " + (c.length() - preview.length())
                + " chars; not persisted: no thread context]");
            // LLM version: full unless it also blows the (large) LLM cap; no ref (not persisted).
            r.setLlmContent(buildLlmVersion(c, effectiveLlmChars, null));
            return;
        }
        String sha = sha256(c);
        Path dir = persistRoot.resolve(ctx.getThread().getId());
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(sha + ".txt");
            persistResult(file, c);
            String preview = safePreview(c, previewChars);
            r.setContent(preview + "\n\n…[truncated " + (c.length() - preview.length())
                + " chars; full persisted to ref:" + sha + "]");
            r.setPersistRef(sha);
            // LLM version: the FULL scrubbed content when within the large LLM cap; only an extreme
            // (multi-100k) result is truncated for the model, and then with a ref hint so the model
            // can read the persisted blob back on demand.
            r.setLlmContent(buildLlmVersion(c, effectiveLlmChars, sha));
        } catch (IOException e) {
            log.warn("Persist tool result failed: {}", e.getMessage());
        }
    }

    /**
     * Build the LLM-facing version of an oversize (already scrubbed) tool result. When {@code c} fits inside the
     * (deliberately large) {@code llmCharCap} the model receives the COMPLETE content — the whole point of decoupling
     * the model view from the small UI preview. Only when it exceeds the LLM cap do we truncate, and then with a
     * model-actionable hint pointing at the persisted full blob (when a {@code ref} exists).
     *
     * @param ref persist ref for the on-disk full blob, or {@code null} when there is no thread context (not
     *            persisted, so the model gets a plain truncation notice without a fetchable pointer).
     */
    private static String buildLlmVersion(String c, int llmCharCap, String ref) {
        if (c.length() <= llmCharCap) {
            return c;
        }
        String preview = safePreview(c, llmCharCap);
        int cut = c.length() - preview.length();
        if (ref != null) {
            return preview + "\n\n…[truncated " + cut
                + " chars for the model. The FULL result was persisted to ref:" + ref
                + "; retrieve the complete content via GET /api/tools/result/{threadId}/" + ref + ".]";
        }
        return preview + "\n\n…[truncated " + cut
            + " chars for the model; full content not persisted (no thread context).]";
    }

    /**
     * Resolve the effective per-tool char budget for {@code content}. When {@link AgentConfig.Budget#getPerToolTokens}
     * is configured (&gt; 0), translate it into a char-equivalent using {@link TokenCounter#charsForTokens} and take
     * the MIN of the two caps so the more restrictive one always wins. Pure-chars path when token cap is disabled.
     */
    private int effectivePerToolChars(String content, ToolContext ctx) {
        int charCap = cfg.getPerToolChars();
        int tokenCap = cfg.getPerToolTokens();
        if (tokenCap <= 0 || tokens == null) {
            return charCap;
        }
        String model = ctx != null && ctx.getThread() != null ? ctx.getThread().getModel() : null;
        int tokenCharBudget = tokens.charsForTokens(content, model, tokenCap);
        // tokenCharBudget == 0 only when content is empty or tokenCap <= 0 (already handled).
        // Guard anyway so a future TokenCounter change cannot accidentally degrade to no truncation.
        return tokenCharBudget > 0 ? Math.min(charCap, tokenCharBudget) : charCap;
    }

    /**
     * Resolve the effective per-tool char budget for the LLM VERSION of {@code content}, mirroring
     * {@link #effectivePerToolChars} but reading {@link AgentConfig.Budget#getPerToolCharsForLlm} /
     * {@link AgentConfig.Budget#getPerToolTokensForLlm}. A char cap of {@code 0} means "no per-tool truncation for the
     * model" and is represented as {@link Integer#MAX_VALUE} (unlimited) so the caller's simple {@code length <= cap}
     * comparison naturally keeps the full content. A token cap can still narrow an otherwise-unlimited char cap.
     */
    private int effectivePerToolCharsForLlm(String content, ToolContext ctx) {
        int charCap = cfg.getPerToolCharsForLlm();
        int tokenCap = cfg.getPerToolTokensForLlm();
        if (charCap <= 0) {
            charCap = Integer.MAX_VALUE; // 0 = disabled => unlimited chars for the model.
        }
        if (tokenCap <= 0 || tokens == null) {
            return charCap;
        }
        String model = ctx != null && ctx.getThread() != null ? ctx.getThread().getModel() : null;
        int tokenCharBudget = tokens.charsForTokens(content, model, tokenCap);
        return tokenCharBudget > 0 ? Math.min(charCap, tokenCharBudget) : charCap;
    }

    public void enforceTurn(List<ToolResult> results, ToolContext ctx) {
        // Two symmetric passes over the SAME batch:
        //  1. UI pass  — bounds the small preview stored in ToolResult.content (unchanged behaviour),
        //               but now snapshots the full content into llmContent BEFORE truncating so the
        //               model channel never inherits a turn-level truncation meant only for the UI.
        //  2. LLM pass — bounds the (deliberately larger) model channel using the *-ForLlm caps.
        enforceTurnUi(results, ctx);
        enforceTurnLlm(results, ctx);
    }

    /** UI-preview turn budget: bounds {@link ToolResult#getContent()} exactly as before, but preserves the full
     * scrubbed content into {@link ToolResult#getLlmContent()} before overwriting it with a preview. */
    private void enforceTurnUi(List<ToolResult> results, ToolContext ctx) {
        // Use long: a few oversize tool results can collectively exceed Integer.MAX_VALUE when
        // each tool returned megabytes of content. The previous int sum silently wrapped negative
        // and caused this whole guard to no-op exactly when it was most needed.
        long sum = 0L;
        for (ToolResult r : results) {
            sum += r.getContent() == null ? 0L : r.getContent().length();
        }
        // Effective turn char cap: when a token cap is configured, derive a char-equivalent for the
        // whole concatenated batch and take MIN(charCap, tokenCharCap). Same min-of-the-two rule as
        // enforcePerTool so operators don't have to reason about which limit fires first.
        int effectiveTurnCap = effectivePerTurnChars(results, ctx);
        if (sum <= effectiveTurnCap) {
            return;
        }

        int budgetPer = Math.max(200, effectiveTurnCap / Math.max(1, results.size()));
        for (ToolResult r : results) {
            String c = r.getContent();
            if (c != null && c.length() > budgetPer) {
                // Persist full content first if not already persisted by enforcePerTool
                if (r.getPersistRef() == null) {
                    String scrubbed = scrub(c);
                    if (!scrubbed.equals(c)) {
                        r.setContent(scrubbed);
                        c = scrubbed;
                    }
                    // This result reached the turn pass without a per-tool persist (small enough to
                    // skip it) but is now being truncated; the scrub above ran SecretRedactor over
                    // its full content, so mark it scrubbed too (belt-and-suspenders: enforcePerTool
                    // already set this for every budget-path result, but keep the invariant local).
                    if (redactionActive()) {
                        r.setScrubbed(true);
                    }
                    // C6 (P2): same null-thread guard as enforcePerTool — skip persistence but keep
                    // truncation so the budget is still honoured.
                    if (ctx.getThread() == null) {
                        // Preserve the FULL content for the model before the UI view is truncated.
                        if (r.getLlmContent() == null) {
                            r.setLlmContent(c);
                        }
                        String ref0 = "";
                        r.setContent(safePreview(c, budgetPer) + "\n…[turn-budget truncated" + ref0 + "]");
                        continue;
                    }
                    String sha = sha256(c);
                    Path dir = persistRoot.resolve(ctx.getThread().getId());
                    try {
                        Files.createDirectories(dir);
                        persistResult(dir.resolve(sha + ".txt"), c);
                        r.setPersistRef(sha);
                    } catch (IOException e) {
                        log.warn("Persist turn-budget result failed: {}", e.getMessage());
                    }
                }
                // Preserve the FULL content for the model before overwriting the UI view. Only
                // captures when enforcePerTool hasn't already set a (full/large) LLM version.
                if (r.getLlmContent() == null) {
                    r.setLlmContent(c);
                }
                String ref = r.getPersistRef() != null ? " ref:" + r.getPersistRef() : "";
                r.setContent(safePreview(c, budgetPer) + "\n…[turn-budget truncated" + ref + "]");
            }
        }
    }

    /**
     * LLM-channel turn budget: bounds the aggregate of the MODEL versions (llmContent, or content when no separate
     * LLM version exists) using {@link AgentConfig.Budget#getPerTurnCharsForLlm} /
     * {@link AgentConfig.Budget#getPerTurnTokensForLlm}. The shipped aggregate cap prevents one parallel batch from
     * immediately refilling a compacted context; operators can still set it to {@code 0} to disable. When it fires,
     * each oversize model version is truncated with a ref hint and the full content is persisted if not already on
     * disk.
     */
    private void enforceTurnLlm(List<ToolResult> results, ToolContext ctx) {
        int cap = effectivePerTurnCharsForLlm(results, ctx);
        if (cap <= 0) {
            return; // 0 = disabled.
        }
        long sum = 0L;
        for (ToolResult r : results) {
            String c = llmView(r);
            sum += c == null ? 0L : c.length();
        }
        if (sum <= cap) {
            return;
        }
        int budgetPer = Math.max(200, cap / Math.max(1, results.size()));
        for (ToolResult r : results) {
            String c = llmView(r);
            if (c == null || c.length() <= budgetPer) {
                continue;
            }
            // c is already scrubbed (enforcePerTool / enforceTurnUi scrub content before it becomes
            // the LLM view); scrub again defensively — idempotent — only when we need to persist a
            // brand-new blob for a result that was small enough to skip the per-tool persist.
            String ref = r.getPersistRef();
            if (ref == null && ctx.getThread() != null) {
                String scrubbed = scrub(c);
                String sha = sha256(scrubbed);
                Path dir = persistRoot.resolve(ctx.getThread().getId());
                try {
                    Files.createDirectories(dir);
                    persistResult(dir.resolve(sha + ".txt"), scrubbed);
                    r.setPersistRef(sha);
                    ref = sha;
                    c = scrubbed;
                    // Full content just passed through SecretRedactor before being persisted +
                    // truncated for the model; the whole result is now scrubbed.
                    if (redactionActive()) {
                        r.setScrubbed(true);
                    }
                } catch (IOException e) {
                    log.warn("Persist turn-budget (LLM) result failed: {}", e.getMessage());
                }
            }
            String preview = safePreview(c, budgetPer);
            int cut = c.length() - preview.length();
            if (ref != null) {
                r.setLlmContent(preview + "\n\n…[turn-budget truncated " + cut
                    + " chars for the model. Full result persisted to ref:" + ref
                    + "; retrieve the complete content via GET /api/tools/result/{threadId}/" + ref + ".]");
            } else {
                r.setLlmContent(preview + "\n\n…[turn-budget truncated " + cut
                    + " chars for the model; full content not persisted (no thread context).]");
            }
        }
    }

    /** The model-facing view of a result: the dedicated {@link ToolResult#getLlmContent()} when present, else the
     * (possibly full) {@link ToolResult#getContent()} as a backwards-compatible fallback. */
    private static String llmView(ToolResult r) {
        return r.getLlmContent() != null ? r.getLlmContent() : r.getContent();
    }

    /**
     * Derive the per-turn char cap honouring both {@link AgentConfig.Budget#getPerTurnChars} and
     * {@link AgentConfig.Budget#getPerTurnTokens}. Concatenates result contents with a single newline separator so the
     * token estimate roughly matches what will be re-fed into the model. Falls back to pure-chars when token cap is
     * disabled or no model is known.
     */
    private int effectivePerTurnChars(List<ToolResult> results, ToolContext ctx) {
        int charCap = cfg.getPerTurnChars();
        int tokenCap = cfg.getPerTurnTokens();
        if (tokenCap <= 0 || tokens == null || results == null || results.isEmpty()) {
            return charCap;
        }
        String model = ctx != null && ctx.getThread() != null ? ctx.getThread().getModel() : null;
        StringBuilder joined = new StringBuilder();
        for (ToolResult r : results) {
            if (r.getContent() != null) {
                joined.append(r.getContent()).append('\n');
            }
        }
        if (joined.length() == 0) {
            return charCap;
        }
        int tokenCharBudget = tokens.charsForTokens(joined.toString(), model, tokenCap);
        return tokenCharBudget > 0 ? Math.min(charCap, tokenCharBudget) : charCap;
    }

    /**
     * Per-turn char cap for the LLM channel, mirroring {@link #effectivePerTurnChars} but reading
     * {@link AgentConfig.Budget#getPerTurnCharsForLlm} / {@link AgentConfig.Budget#getPerTurnTokensForLlm} and
     * concatenating the MODEL views ({@link #llmView}). Returns {@code 0} to signal "disabled" when neither the char
     * nor the token cap is set.
     */
    private int effectivePerTurnCharsForLlm(List<ToolResult> results, ToolContext ctx) {
        int charCap = cfg.getPerTurnCharsForLlm();
        int tokenCap = cfg.getPerTurnTokensForLlm();
        if (charCap <= 0 && tokenCap <= 0) {
            return 0; // both disabled => LLM turn budget off.
        }
        if (charCap <= 0) {
            charCap = Integer.MAX_VALUE; // only a token cap is set; chars unbounded.
        }
        if (tokenCap <= 0 || tokens == null || results == null || results.isEmpty()) {
            return charCap;
        }
        String model = ctx != null && ctx.getThread() != null ? ctx.getThread().getModel() : null;
        StringBuilder joined = new StringBuilder();
        for (ToolResult r : results) {
            String c = llmView(r);
            if (c != null) {
                joined.append(c).append('\n');
            }
        }
        if (joined.length() == 0) {
            return charCap;
        }
        int tokenCharBudget = tokens.charsForTokens(joined.toString(), model, tokenCap);
        return tokenCharBudget > 0 ? Math.min(charCap, tokenCharBudget) : charCap;
    }

    /**
     * UTF-16 code-unit safe preview that prefers a clean line boundary. Avoids cutting in the middle of a surrogate
     * pair (which would render as a Unicode replacement char on the model side) and, when possible, ends at the last
     * newline within the budget so log/code-style output stays parseable.
     */
    private static String safePreview(String c, int budgetChars) {
        int n = Math.min(budgetChars, c.length());
        // If we're cutting mid surrogate pair, drop the dangling high surrogate.
        if (n > 0 && n < c.length() && Character.isHighSurrogate(c.charAt(n - 1))) {
            n--;
        }
        // Try to back up to the last newline within the second half of the budget.
        int floor = Math.max(0, n - Math.min(budgetChars / 4, 1024));
        int nl = c.lastIndexOf('\n', n - 1);
        if (nl >= floor) {
            n = nl;
        }
        return c.substring(0, n);
    }

    /**
     * Read back a previously persisted (truncated) tool result. Used by the {@code /api/tools/result/{tid}/{ref}}
     * endpoint so the UI can lazy-fetch the full payload when the user expands a ref-stub.
     *
     * <p>
     * {@code ref} is validated to a 16-hex-char SHA prefix and resolved strictly within the per-thread directory \u2014
     * path-traversal attempts return {@link Optional#empty()} rather than escaping the sandbox.
     */
    public Optional<String> readPersisted(String threadId, String ref) {
        if (threadId == null || ref == null)
            return Optional.empty();
        if (!ref.matches("[0-9a-f]{16}"))
            return Optional.empty();
        if (!Ids.isSafe(threadId))
            return Optional.empty();
        Path dir = persistRoot.resolve(threadId).normalize();
        Path file = dir.resolve(ref + ".txt").normalize();
        // Lexical containment is necessary but NOT sufficient: an attacker (e.g. the LLM via
        // bash/file_write) could plant a symlink at <persistRoot>/<thread>/<ref>.txt pointing
        // to /etc/passwd or another thread's blob. startsWith only inspects path strings.
        // Defence-in-depth:
        // 1. Reject symlinks outright via NOFOLLOW_LINKS — persisted refs are always
        // regular files written by enforcePerTool above.
        // 2. Resolve to the canonical real path and re-check containment against the
        // canonical persistRoot, so a symlink anywhere in the parent chain is caught.
        if (!file.startsWith(persistRoot.normalize()))
            return Optional.empty();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            return Optional.empty();
        try {
            Path realFile = file.toRealPath();
            Path realRoot = persistRoot.toRealPath();
            if (!realFile.startsWith(realRoot)) {
                log.warn("readPersisted: canonical path escape thread={} ref={}", threadId, ref);
                return Optional.empty();
            }
            return Optional.of(Files.readString(realFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("read persisted ref {} failed: {}", ref, e.getMessage());
            return Optional.empty();
        }
    }

    private static void persistResult(Path file, String content) throws IOException {
        // Content-addressed results are immutable. Publish complete bytes before another client can read them.
        if (!Files.exists(file)) TextEditCore.atomicWrite(file, content);
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /**
     * Recursively delete {@code <dataDir>/agent/tool-results/<threadId>/}. Wired by {@link com.harnex.application.session.SessionHooks} into
     * {@code SessionStore.onThreadDeleted} so persisted full-payload blobs (created by {@link #enforcePerTool} /
     * {@link #enforceTurn} when a single tool result exceeds {@code per-tool-chars}) do not accumulate forever on a
     * long-running deployment. Without this hook the directory grew unbounded — every other per-thread cache (uploads,
     * sandbox, snapshots, approval whitelist, todos, plan-mode, file-access tracker, path protection) was already
     * purged on thread delete.
     *
     * <p>
     * Best-effort: a failure here is logged but never propagated, matching the rest of the thread-deletion listeners
     * (see {@code SessionHooks.safe()}).
     */
    public void purgeThread(String threadId) {
        if (threadId == null || threadId.isBlank())
            return;
        if (!Ids.isSafe(threadId))
            return;
        Path dir = persistRoot.resolve(threadId);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS))
            return;
        try {
            // Defence against a symlink planted at <persistRoot>/<thread> pointing outside:
            // resolve real paths and re-check containment before recursing.
            Path realDir = dir.toRealPath();
            Path realRoot = persistRoot.toRealPath();
            if (!realDir.startsWith(realRoot)) {
                log.warn("purgeThread: refusing to delete out-of-root path thread={} resolved={}", threadId, realDir);
                return;
            }
            try (Stream<Path> walk = Files.walk(realDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        log.debug("purgeThread: skip {}: {}", p, e.getMessage());
                    }
                });
            }
        } catch (IOException e) {
            log.warn("purgeThread {} failed: {}", threadId, e.getMessage());
        }
    }
}
