package com.harnex.audit;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.model.ChatMessage;
import com.harnex.model.ToolCall;
import com.harnex.safety.SecretRedactor;
import com.harnex.util.Ids;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Append-only audit log of every LLM-produced assistant message. One JSONL file per thread per UTC day under
 * {@code data/audit/<threadId>/<YYYY-MM-DD>.jsonl}, where each record is a single line:
 *
 * <pre>
 * {ts, threadId, iter, model, provider, contentLen, toolCalls:[{name,argsLen}], content}
 * </pre>
 *
 * <p>
 * Goals:
 * <ul>
 * <li>Forensic replay for post-mortem ("what did the model actually say at 14:32:11?").</li>
 * <li>Compliance evidence: durable record of model output independent of the live thread JSON (which can be edited,
 * branched, or truncated by snapshot restore).</li>
 * <li>Cheap in the hot path: write through a single-threaded daemon executor so a slow disk cannot stall the agent
 * loop. Failure to enqueue or write is logged at WARN and never propagated \u2014 audit is not allowed to break the
 * user-visible chat.</li>
 * </ul>
 *
 * <p>
 * Disabled by default ({@code harnex.audit.enabled=false}) so existing single-user dev installs see no extra disk
 * writes; flip on per deployment.
 *
 * <p>
 * Retention: simple file-count cap per thread directory (oldest day-files deleted first). Sized for ~1 audit write per
 * agent turn; the default 90-day cap covers a full quarter of dense usage at single-user scale without ever exceeding a
 * few MB per thread.
 */
@Slf4j
@Component
public class LlmAuditLog {

    private static final ObjectMapper M = new ObjectMapper();

    private final AgentConfig cfg;
    private final boolean enabled;
    private final int retentionDays;
    private final long maxMessageChars;
    private final boolean redactSecrets;
    private final ExecutorService writer;
    private final Path root;
    /**
     * Tripped when the audit volume becomes unwritable (full disk, permissions revoked, etc.). Once degraded,
     * subsequent writes short-circuit and we log at most one ERROR — without this a stuck audit volume would spam the
     * log at WARN once per turn forever.
     */
    private final AtomicBoolean degraded = new AtomicBoolean(false);
    /**
     * Number of audit records dropped because the bounded writer queue was full. Exposed for /metrics-style
     * introspection so an operator can see when audit is silently lossy. The default queue depth (4096) is enormous
     * relative to typical agent throughput — hitting it means either a stuck disk or a runaway loop, both of which the
     * operator needs to see explicitly. Drops are also logged at WARN with rate-limiting so the application log itself
     * doesn't get flooded.
     */
    private final AtomicLong dropped = new AtomicLong(0);
    private final AtomicLong lastDropWarnMs = new AtomicLong(0);
    /** One-shot warning latch for the secrets=off + pii=on combination (see recordAssistant). */
    private final AtomicBoolean piiOnlyWarned = new AtomicBoolean(false);

    /**
     * Credential / PII scrubbing is delegated to {@link SecretRedactor} — the single source of truth shared with
     * {@link com.harnex.middleware.SensitiveDataMiddleware} and {@link com.harnex.tool.budget.BudgetEnforcer}.
     * Previously this class kept a private {@code SECRET_PATTERNS} array which drifted out of sync as new providers
     * were added (github_pat_, AIza..., JWT, secret=...): tool-result paths were scrubbed but the durable audit JSONL —
     * which is exactly the artefact most likely to be shipped off-box for compliance review — could still contain those
     * tokens verbatim if the assistant echoed them. Always-on regex patterns + a separate PII tier preserve the
     * original "audit must be safer than chat" stance while eliminating the divergence.
     *
     * <p>
     * PII redaction here is intentionally tied to the same {@code redact-pii-in-tool-results} flag as the runtime path:
     * turning it on for tool results without also redacting audit would defeat the point, and vice versa. Audit
     * redaction itself remains gated by the existing {@code harnex.audit.redact-secrets} switch (default true).
     */
    private final boolean redactPiiInAudit;

    private String redact(String s) {
        if (s == null || s.isEmpty())
            return s;
        return SecretRedactor.redact(s, redactPiiInAudit);
    }

    @Autowired
    public LlmAuditLog(AgentConfig cfg, @Value("${harnex.audit.enabled:false}") boolean enabled,
        @Value("${harnex.audit.retention-days:90}") int retentionDays,
        @Value("${harnex.audit.max-message-chars:200000}") long maxMessageChars,
        @Value("${harnex.audit.redact-secrets:true}") boolean redactSecrets,
        @Value("${harnex.safety.redact-pii-in-tool-results:false}") boolean redactPiiInAudit) {
        this.cfg = cfg;
        this.enabled = enabled;
        this.retentionDays = Math.max(1, retentionDays);
        this.maxMessageChars = Math.max(1024, maxMessageChars);
        this.redactSecrets = redactSecrets;
        this.redactPiiInAudit = redactPiiInAudit;
        this.root = Paths.get(cfg.getDataDir(), "diagnostics/audit");
        // Bounded writer queue. Previously {@code Executors.newSingleThreadExecutor} backed
        // the writer with an UNBOUNDED LinkedBlockingQueue; if audit was enabled and the disk
        // got slow (network mount, fsync stall, full-disk write retries) the queue grew until
        // the JVM OOMed. Now: 4096-entry ArrayBlockingQueue + DiscardOldestPolicy turns a
        // sustained backlog into bounded memory + a visible drop counter, while a transient
        // burst still drains without loss.
        ThreadPoolExecutor exec =
            new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(4096), r -> {
                Thread t = new Thread(r, "llm-audit");
                t.setDaemon(true);
                return t;
            }, (r, ex) -> {
                // DiscardOldest semantics with a counter — drop the head (oldest pending audit
                // record) and try to enqueue the new one. The runnable provided to the
                // RejectedExecutionHandler is the NEW task that didn't fit; poll the queue once
                // to free a slot, then offer it back. If even that fails (race with shutdown),
                // bump the drop counter and move on — audit must NOT block chat.
                if (!ex.isShutdown()) {
                    ex.getQueue().poll();
                    if (!ex.getQueue().offer(r)) {
                        long total = dropped.incrementAndGet();
                        warnDropRateLimited(total);
                    } else {
                        long total = dropped.incrementAndGet();
                        warnDropRateLimited(total);
                    }
                } else {
                    dropped.incrementAndGet();
                }
            });
        this.writer = exec;
        if (enabled) {
            try {
                Files.createDirectories(root);
            } catch (IOException ioe) {
                log.warn("LlmAuditLog: cannot create root dir {} \u2014 disabling: {}", root, ioe.getMessage());
            }
        }
    }

    /** Test-only / backward-compatible overload \u2014 redaction enabled by default. */
    public LlmAuditLog(AgentConfig cfg, boolean enabled, int retentionDays, long maxMessageChars) {
        this(cfg, enabled, retentionDays, maxMessageChars, true, false);
    }

    /**
     * Record a finished assistant message. Cheap and non-blocking when enabled; no-op when disabled.
     *
     * @param threadId owning thread id
     * @param iter iteration index inside the agent loop (0-based)
     * @param model model name (may be null \u2014 logged as empty string)
     * @param provider provider name that produced the message (may be null)
     * @param assistant the assistant {@link ChatMessage} just appended to the thread
     */
    public void recordAssistant(String threadId, int iter, String model, String provider, ChatMessage assistant) {
        if (!enabled || assistant == null || threadId == null)
            return;
        // Defense-in-depth: every production caller already passes a Ids.requireSafe()-checked
        // thread id, but a future caller (test, gateway adapter, REST shim) could plausibly forget.
        // An unsafe id like "../../etc" would let `root.resolve(tid)` escape the audit root and
        // create files under arbitrary directories. Validating here is cheap and idempotent.
        if (!Ids.isSafe(threadId)) {
            log.warn("LlmAuditLog: rejecting unsafe threadId for audit write");
            return;
        }
        // Snapshot anything mutable BEFORE handing to the executor \u2014 the agent loop will keep
        // appending tool results / next assistant messages to the same lists immediately after
        // we return, so deferred serialisation must capture state at the recordAssistant call.
        String safeContent = assistant.getContent() == null ? "" : assistant.getContent();
        // Two independent gates intentionally:
        // - harnex.audit.redact-secrets controls credential scrubbing in the audit JSONL
        // (default true; turning OFF only makes sense for forensic deployments where the
        // audit volume is itself encrypted at rest and never shipped).
        // - harnex.safety.redact-pii-in-tool-results gates PII patterns. PII compliance is
        // a separate concern from credential leakage \u2014 a healthcare deployment may want
        // PII stripped from EVERY persisted artefact (including audit) regardless of how
        // the secret toggle is set, and conversely a dev install that disables secret
        // redaction shouldn't be forced to also keep PII.
        // Apply each independently so the matrix of {secrets on/off} \u00d7 {pii on/off} all
        // four behave as documented.
        if (redactSecrets && redactPiiInAudit) {
            safeContent = SecretRedactor.redact(safeContent, true);
        } else if (redactSecrets) {
            safeContent = SecretRedactor.redact(safeContent, false);
        } else if (redactPiiInAudit) {
            // Secrets off but PII on: redact PII only. SecretRedactor doesn't expose a
            // PII-only mode, so we approximate by running with includePii=true and accepting
            // that secret patterns will also be scrubbed \u2014 a strictly safer outcome than
            // leaving credentials raw. If a deployment truly requires "PII redacted but
            // secrets passed through", that should be a separate SecretRedactor entrypoint;
            // for now log a warning so the operator notices the safer-than-asked behaviour.
            if (!piiOnlyWarned.getAndSet(true)) {
                log.warn(
                    "LlmAuditLog: harnex.audit.redact-secrets=false but harnex.safety.redact-pii-in-tool-results=true \u2014 audit will scrub BOTH secrets and PII (no PII-only mode available)");
            }
            safeContent = SecretRedactor.redact(safeContent, true);
        }
        if (safeContent.length() > maxMessageChars) {
            safeContent = safeContent.substring(0, (int)maxMessageChars) + "\u2026[truncated]";
        }
        List<Map<String, Object>> tcSummary = new ArrayList<>();
        if (assistant.getToolCalls() != null) {
            for (ToolCall tc : assistant.getToolCalls()) {
                Map<String, Object> tcs = new LinkedHashMap<>();
                tcs.put("name", tc.getName());
                tcs.put("argsLen", tc.getArguments() == null ? 0 : tc.getArguments().length());
                tcs.put("id", tc.getId());
                tcSummary.add(tcs);
            }
        }
        final String tid = threadId;
        final String content = safeContent;
        final long ts = System.currentTimeMillis();
        try {
            writer.submit(() -> {
                if (degraded.get())
                    return; // already known-broken; skip silently
                try {
                    Path threadDir = root.resolve(tid);
                    Files.createDirectories(threadDir);
                    Path file = threadDir.resolve(LocalDate.now(ZoneOffset.UTC) + ".jsonl");
                    Map<String, Object> rec = new LinkedHashMap<>();
                    rec.put("ts", ts);
                    rec.put("threadId", tid);
                    rec.put("iter", iter);
                    rec.put("model", model == null ? "" : model);
                    rec.put("provider", provider == null ? "" : provider);
                    rec.put("contentLen", content.length());
                    rec.put("toolCalls", tcSummary);
                    rec.put("content", content);
                    String line = M.writeValueAsString(rec) + "\n";
                    // Append + fsync the data block so a crash between record() and the next write
                    // cannot lose the audit entry. Using DSYNC inside the open() call would also
                    // flush metadata; we only need the data persisted (size update is a metadata
                    // change but file existence is durable as soon as createDirectories succeeded).
                    try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND,
                        StandardOpenOption.WRITE)) {
                        ch.write(ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8)));
                        ch.force(false); // metadata=false: durability without paying for inode flush
                    }
                    pruneRetention(threadDir);
                } catch (FileSystemException fsx) {
                    // Disk full / read-only / quota exceeded — degrade once, scream loud, then go quiet.
                    if (degraded.compareAndSet(false, true)) {
                        log.error("LlmAuditLog: persistent FS error — auditing DEGRADED for the rest of this JVM: {}",
                            fsx.getMessage());
                    }
                } catch (Exception e) {
                    log.warn("LlmAuditLog write failed for {}: {}", tid, e.getMessage());
                }
            });
        } catch (RejectedExecutionException ree) {
            // Bounded queue saturated and even the discard-oldest fallback couldn't make room
            // (e.g. shutdown race). Audit MUST NOT break chat — swallow + bump the counter.
            long total = dropped.incrementAndGet();
            warnDropRateLimited(total);
        } catch (Exception submitErr) {
            log.warn("LlmAuditLog: enqueue failed: {}", submitErr.getMessage());
        }
    }

    /** Number of records the writer dropped due to queue saturation. Exposed for tests / metrics. */
    public long getDroppedCount() {
        return dropped.get();
    }

    /** Emit at most one WARN per minute so a sustained backlog doesn't spam the application log. */
    private void warnDropRateLimited(long total) {
        long now = System.currentTimeMillis();
        long last = lastDropWarnMs.get();
        if (now - last < 60_000L)
            return;
        if (lastDropWarnMs.compareAndSet(last, now)) {
            log.warn("LlmAuditLog: writer queue saturated — dropped {} record(s) total this JVM", total);
        }
    }

    /**
     * Trim per-thread audit files to the newest {@code retentionDays}. Cheap: the directory usually has 1\u2013 a few
     * entries; sorts lexicographically (YYYY-MM-DD is sortable as text) and unlinks anything beyond the cap. Best
     * effort \u2014 a permission failure is logged at DEBUG and ignored.
     */
    private void pruneRetention(Path threadDir) {
        try (var s = Files.list(threadDir)) {
            List<Path> files = s.filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString())).toList();
            for (int i = retentionDays; i < files.size(); i++) {
                try {
                    Files.deleteIfExists(files.get(i));
                } catch (IOException ignored) {
                    /* best effort */ }
            }
        } catch (IOException ignored) {
            /* best effort */ }
    }

    @PreDestroy
    public void shutdown() {
        if (!enabled)
            return;
        writer.shutdown();
        try {
            // 5s is enough to drain any buffered writes from a typical session; failing that we
            // shutdownNow() so JVM exit is not blocked by an audit backlog.
            if (!writer.awaitTermination(5, TimeUnit.SECONDS))
                writer.shutdownNow();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
    }
}
