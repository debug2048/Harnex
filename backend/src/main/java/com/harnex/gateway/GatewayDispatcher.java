package com.harnex.gateway;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.core.AgentConversationSink;
import com.harnex.core.AgentExecutionContext;
import com.harnex.core.AgentExecutionOptions;
import com.harnex.core.AgentExecutionRegistry;
import com.harnex.core.AgentLoop;
import com.harnex.core.PermissionContext;
import com.harnex.execution.PlanReviewPolicy;
import com.harnex.core.StepLimits;
import com.harnex.core.StepRequest;
import com.harnex.core.StepResult;
import com.harnex.model.ChatMessage;
import com.harnex.model.ConversationThread;
import com.harnex.model.StreamEvent;
import com.harnex.model.ThreadState;
import com.harnex.run.ActiveAgentRegistry;
import com.harnex.session.SessionStore;
import com.harnex.run.RunCoordinator;
import com.harnex.run.RunCoordinator.BeginResult;
import com.harnex.run.RunCoordinator.RunLease;
import com.harnex.run.RunCoordinator.RunSpec;
import com.harnex.run.RunOrigin;
import com.harnex.execution.TextTarget;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Shared Agent submission and conversation binding for HTTP callbacks and native IM transports.
 *
 * <p>
 * Handles thread stickiness (same external user → same agent thread), invokes the {@link AgentLoop}, persists the
 * conversation, and returns the last assistant reply.
 *
 * <p>
 * Supports both synchronous dispatch (returns inline, blocking) and asynchronous dispatch (returns immediately with
 * "processing..." placeholder, then calls a callback with the result). For longer-running agent tasks, use
 * asyncDispatch() with a ReplyCallback to push results back to the IM platform.
 */
@Slf4j
@Service
@ProjectRuntime
public class GatewayDispatcher {

    private RunSpec entrySpec(String threadId, String text, String channel, String bindKey) {
        return entrySpec(threadId, text, channel, bindKey, executionOptions(), null, null);
    }

    private RunSpec entrySpec(String threadId, String text, String channel, String bindKey,
        AgentExecutionOptions options, TextTarget target, String permissionMode) {
        RunSpec spec = new RunSpec(new RunOrigin(RunOrigin.Kind.GATEWAY, bindKey), options,
            target == null ? null : target.provider(), target == null ? null : target.model(),
            target == null ? null : target.reasoningEffort(), null, null, permissionMode);
        return archive == null ? spec : archive.prepare(spec, threadId, text, channel, null);
    }

    @Autowired(required = false)
    private AgentConversationSink archive;
    @Autowired(required = false)
    private AgentExecutionRegistry agentExecutions =
        new AgentExecutionRegistry(new ActiveAgentRegistry());

    private final AgentLoop loop;
    private final SessionStore store;
    private final AgentConfig cfg;
    private final ExecutorService asyncExecutor;
    private final ScheduledThreadPoolExecutor timeouts = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "gateway-timeouts"); thread.setDaemon(true); return thread;
    });
    /**
     * Optional — present in the Spring runtime, may be null in unit tests that wire a GatewayDispatcher directly. Used
     * to (a) participate in the WebUI's single-flight gate so a thread driven by both the IM channel and the WebUI
     * can't run two AgentLoops in parallel, (b) let thread/run cancellation actually interrupt a running
     * gateway loop, and (c) let {@code DELETE /api/threads/{tid}} signal the in-flight gateway loop to abort cleanly
     * via the tombstone hint that {@code RunCoordinator} also consults before admission. The
     * save-resurrects-deleted-thread bug is closed at the SessionStore layer (per-thread tombstone gate) regardless of
     * whether this is wired — this just makes cancel/abort work end-to-end for IM users too.
     */
    private final RunCoordinator runs;
    private static final ObjectMapper BIND_M = new ObjectMapper();

    /**
     * "<channel>|<user>|<threadKey>" -> threadId. Persisted to {@code <dataDir>/gateway-bindings.json} so a JVM restart
     * doesn't strand IM users on a brand-new agent thread (which would otherwise silently drop all conversational
     * context).
     */
    private final Map<String, String> threadBinding = new ConcurrentHashMap<>();
    private Path bindingsFile;

    /**
     * Per-bindKey 1-permit semaphore: prevents one IM user from queueing dozens of agent runs in parallel and
     * exhausting the executor. tryAcquire fails fast with a "still processing" reply.
     */
    private final Map<String, Semaphore> userPermits = new ConcurrentHashMap<>();
    /** Hard timeout on a single async dispatch \u2014 5 minutes is more than enough for any sane IM workflow. */
    private static final long ASYNC_DISPATCH_TIMEOUT_MS = 5 * 60_000L;
    /**
     * Idempotency cache: IM platforms (DingTalk / Feishu / WeCom) silently re-deliver webhooks if our ack times out, so
     * the same (user, message) tuple can arrive 2–3 times within seconds. Caching the prior reply for a short window
     * prevents duplicate agent runs (and duplicate token spend). Key = bindKey + "#" + (messageId or "text:" +
     * sha1(text)).
     */
    private static final long IDEMPOTENCY_TTL_MS = 2L * 60_000L;
    /**
     * Much shorter TTL when we have to fall back to a content-hash key (no platform messageId available). Two minutes
     * was long enough that legitimate "继续" / "再试一次" / "?" replies were being silently swallowed because the previous
     * reply was returned from cache. 10 seconds still covers the IM-platform redelivery window for "no-ack" retries
     * (DingTalk/Feishu/WeCom all retry within ~5s) without blocking a user who deliberately resends the same text.
     */
    private static final long IDEMPOTENCY_TEXT_FALLBACK_TTL_MS = 10_000L;
    private final Map<String, IdempotentEntry> recentReplies = new ConcurrentHashMap<>();

    /** One-shot dedup of "denied because allowlist empty" warnings, keyed by channel|user. */
    private final Set<String> allowDenyLogged = ConcurrentHashMap.newKeySet();

    private record IdempotentEntry(String reply, String threadId, long expiresAt) {
    }

    /**
     * Build an idempotency key. Prefers the platform-supplied messageId (truly unique per delivery, so it dedups exact
     * redeliveries forever within the TTL without ever swallowing a legitimate retype). Falls back to a content hash
     * with a much shorter TTL when the platform didn't send one.
     *
     * <p>
     * The dispatcher's idempotency cache is the SECOND line of defence against platform re-delivery (the FIRST is the
     * gateway's nonce/sign replay cache). Even with async push-back mode (gateway ACKs the webhook in &lt;100ms so the
     * platform rarely retries), network drops still occasionally trigger retries and the cache prevents the second
     * arrival from launching a parallel agent run on the same conversation.
     */
    private static String buildIdemKey(String bindKey, String messageId, String text) {
        if (messageId != null && !messageId.isBlank()) {
            return bindKey + "#msg:" + messageId;
        }
        return bindKey + "#text:" + contentHash(text);
    }

    /** Pick the cache TTL appropriate for the kind of key we built. */
    private static long idemTtlFor(String messageId) {
        return (messageId != null && !messageId.isBlank()) ? IDEMPOTENCY_TTL_MS : IDEMPOTENCY_TEXT_FALLBACK_TTL_MS;
    }

    private static String contentHash(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    private void sweepIdempotencyCache() {
        long now = System.currentTimeMillis();
        // Soft sweep first: drop expired entries. Use >= so the subsequent put doesn't
        // transiently exceed the cap (sweep is called before put).
        if (recentReplies.size() >= IDEMPOTENCY_MAX_ENTRIES) {
            recentReplies.entrySet().removeIf(e -> e.getValue().expiresAt < now);
        }
        // Hard cap: if a sustained burst of legitimate-but-distinct messages keeps every
        // entry inside its TTL window, the soft sweep above reclaims nothing and the cache
        // would grow unbounded. Force-evict the entries closest to expiry until back under
        // a 90% high-water mark. This bounds heap usage in a worst-case IM-flood scenario
        // (the only cost of force-eviction is losing idempotency on the dropped entries
        // \u2014 a re-delivery of one of those would re-run the agent rather than serving from
        // cache, which is correctness-preserving but wastes one extra LLM call).
        if (recentReplies.size() >= IDEMPOTENCY_MAX_ENTRIES) {
            int target = IDEMPOTENCY_MAX_ENTRIES * 9 / 10;
            recentReplies.entrySet().stream().sorted(Comparator.comparingLong(e -> e.getValue().expiresAt))
                .limit(Math.max(0, recentReplies.size() - target)).map(Map.Entry::getKey).toList()
                .forEach(recentReplies::remove);
        }
    }

    /** Hard ceiling on the idempotency cache. Sized for ~1024 distinct in-flight conversations. */
    private static final int IDEMPOTENCY_MAX_ENTRIES = 1024;

    private AgentExecutionOptions executionOptions() {
        return new AgentExecutionOptions(PermissionContext.READ_ONLY,
            StepLimits.resolve(cfg, null, cfg.getMaxIterations()), null,
            PlanReviewPolicy.AUTOMATIC);
    }

    public GatewayDispatcher(AgentLoop loop, SessionStore store, AgentConfig cfg, RunCoordinator runs) {
        this.loop = loop;
        this.store = store;
        this.cfg = cfg;
        this.runs = runs;
        // Bounded executor — back-pressure surfaces as RejectedExecutionException, NOT as an
        // unbounded LinkedBlockingQueue silently absorbing IM/webhook bursts that would later
        // turn into a latency avalanche when the LLM provider is slow. The per-bindKey
        // {@link #userPermits} semaphore ALREADY caps concurrent runs per user to 1, so the
        // queue only needs to absorb cross-user fanout from a brief spike. Sized at 4× core so
        // a moderate burst still queues briefly instead of being rejected on the very first
        // arrival, but pathological floods (or a wedged provider) fail fast with a structured
        // "busy" reply via the catch in asyncDispatch.
        int core = Math.max(2, Runtime.getRuntime().availableProcessors());
        int max = core * 2;
        int queue = core * 4;
        ThreadPoolExecutor exec =
            new ThreadPoolExecutor(core, max, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(queue), r -> {
                Thread t = new Thread(r, "gateway-async-" + Thread.currentThread().getId());
                t.setDaemon(false);
                return t;
            }, new ThreadPoolExecutor.AbortPolicy());
        exec.allowCoreThreadTimeOut(false);
        this.asyncExecutor = exec;
        timeouts.setRemoveOnCancelPolicy(true);
    }

    /** User-visible busy reply when the bounded async executor is saturated. */
    public static final String BUSY_REPLY = "\u23f3 \u670d\u52a1\u7e41\u5fd9\uff0c\u8bf7\u7a0d\u5019\u91cd\u8bd5\u3002";

    /** Reply returned when the sender is not in {@code harnex.gateway.allowed-senders}. */
    public static final String UNAUTHORIZED_SENDER_REPLY =
        "\u26d4 \u672a\u6388\u6743\u7684\u53d1\u9001\u8005\uff0c\u8bf7\u8054\u7cfb\u7ba1\u7406\u5458\u52a0\u767d\u540d\u5355\u3002";

    /** Reply returned when this user/thread already has a run in flight (single-flight refusal). */
    public static final String PREVIOUS_RUN_ACTIVE_REPLY =
        "\u23f3 \u4e0a\u4e00\u8f6e\u4ecd\u5728\u5904\u7406\u4e2d\uff0c\u8bf7\u7a0d\u5019\u518d\u53d1\u3002";

    /** Reply returned when another concurrent run already holds the thread reservation. */
    public static final String THREAD_BUSY_REPLY =
        "\u23f3 \u8be5\u4f1a\u8bdd\u4ecd\u5728\u5904\u7406\u4e2d\uff0c\u8bf7\u7a0d\u5019\u518d\u53d1\u3002";

    /**
     * Apply per-channel toolset / model defaults on a fresh thread.
     *
     * <p>
     * Operators can configure {@code harnex.gateway.channelDefaults.{channel}.toolsets} (and {@code .model}) in YAML so
     * a Feishu group reaches the agent in {@code read} mode while a DingTalk admin channel starts in {@code act} mode.
     * Pure default — once the thread is persisted the user / model can change toolsets via the normal mechanisms and
     * subsequent turns honour that. {@code null} channel and unconfigured channels are noops.
     */
    private void applyChannelDefaults(ConversationThread t, String channel) {
        if (cfg == null || cfg.getGateway() == null || channel == null || channel.isBlank())
            return;
        var defaults = cfg.getGateway().getChannelDefaults();
        if (defaults == null || defaults.isEmpty())
            return;
        var entry = defaults.get(channel.trim().toLowerCase(Locale.ROOT));
        if (entry == null)
            return;
        if (entry.getToolsets() != null && !entry.getToolsets().isEmpty()) {
            t.setToolsets(new ArrayList<>(entry.getToolsets()));
        }
        if (entry.getModel() != null && !entry.getModel().isBlank()) {
            t.setModel(entry.getModel());
        }
    }

    /**
     * Returns {@code true} when the inbound sender is allowed.
     *
     * <p>
     * <b>Default policy: fail-closed.</b> An empty / unset {@code allowed-senders} list denies every inbound IM /
     * webhook sender. Earlier versions treated empty as "allow all"; that was wrong for IM/Webhook entrypoints because
     * the platform secret only proves "request came from Feishu/DingTalk/WeCom/Alertmanager", not "the human is
     * authorised to drive this agent". Operators who genuinely want unrestricted access must set the wildcard
     * {@code "*"} explicitly.
     *
     * <p>
     * Entry formats:
     * <ul>
     * <li>{@code "*"} — wildcard, allows every sender on every channel.</li>
     * <li>{@code "user-id"} — matches that user on any channel.</li>
     * <li>{@code "channel:user-id"} — matches only when the inbound channel matches too.</li>
     * </ul>
     */
    private boolean senderAllowed(String channel, String user) {
        List<String> allow = cfg == null || cfg.getGateway() == null ? null : cfg.getGateway().getAllowedSenders();
        if (allow == null || allow.isEmpty()) {
            // Fail-closed. Log once per (channel,user) pair so the operator can see who is
            // being rejected and add them — without flooding the log on a chatty group.
            if (allowDenyLogged.add((channel == null ? "" : channel) + "|" + (user == null ? "" : user))) {
                log.warn("Gateway sender denied (channel={}, user={}): allowed-senders is empty. "
                    + "Set harnex.gateway.allowed-senders to [\"*\"] to allow all, or list specific "
                    + "ids / channel:id pairs.", channel, user);
            }
            return false;
        }
        if (allow.size() == 1 && "*".equals(allow.get(0).trim()))
            return true;
        String c = channel == null ? "" : channel.trim().toLowerCase(Locale.ROOT);
        String u = user == null ? "" : user.trim();
        if (u.isEmpty())
            return false;
        String uLower = u.toLowerCase(Locale.ROOT);
        for (String entry : allow) {
            if (entry == null)
                continue;
            String e = entry.trim();
            if (e.isEmpty() || e.startsWith("#"))
                continue;
            if ("*".equals(e))
                return true;
            int colon = e.indexOf(':');
            if (colon < 0) {
                if (e.equalsIgnoreCase(u))
                    return true;
            } else {
                String ec = e.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String eu = e.substring(colon + 1).trim();
                if (ec.equals(c) && eu.equalsIgnoreCase(uLower))
                    return true;
            }
        }
        return false;
    }

    /**
     * Load persisted bindings on startup so IM users keep their sticky thread across restarts. Failure to load is
     * non-fatal — worst case is one lost-context message until rebinding occurs.
     */
    @PostConstruct
    void loadBindings() {
        try {
            String dataDir = (cfg == null ? new AgentConfig() : cfg).getDataDir();
            this.bindingsFile = Paths.get(dataDir, "automation/gateway-bindings.json");
            if (Files.isRegularFile(bindingsFile)) {
                Map<String, String> persisted = BIND_M.readValue(Files.readString(bindingsFile, StandardCharsets.UTF_8),
                    new TypeReference<Map<String, String>>() {});
                if (persisted != null) {
                    threadBinding.putAll(persisted);
                    log.info("GatewayDispatcher loaded {} persisted bindings from {}", persisted.size(), bindingsFile);
                }
            }
        } catch (Exception e) {
            log.warn("GatewayDispatcher failed to load bindings ({}): {}", bindingsFile, e.getMessage());
        }
        // Subscribe to thread deletion so a WebUI delete also cleans up the IM binding.
        // Without this, threadBinding kept the deleted threadId on disk and the next IM message
        // from the same external user resolved back to it — FtsSessionStore.save then refused
        // the resurrection (see tombstone gate), so the agent silently produced replies that
        // never showed up in the sidebar / history until the 1h tombstone TTL expired. Cleaning
        // the binding triggers resolveThreadId to mint a fresh thread id on the next message,
        // which is the right user-facing behaviour ("the chat is gone — start a new one").
        try {
            store.onThreadDeleted(deletedId -> {
                if (deletedId == null || deletedId.isEmpty())
                    return;
                List<String> victims = new ArrayList<>();
                for (var e : threadBinding.entrySet()) {
                    if (deletedId.equals(e.getValue()))
                        victims.add(e.getKey());
                }
                if (victims.isEmpty())
                    return;
                for (String k : victims) {
                    threadBinding.remove(k, deletedId);
                }
                log.info("GatewayDispatcher purged {} IM binding(s) for deleted thread {}", victims.size(), deletedId);
                persistBindings();
            });
        } catch (Exception e) {
            log.warn("GatewayDispatcher failed to register thread-delete listener: {}", e.getMessage());
        }
    }

    /**
     * Best-effort atomic write of the binding map. Called whenever a new bindKey -> threadId association is created OR
     * a thread-delete listener purges stale bindings. Errors are logged-and-swallowed; binding will be re-persisted on
     * the next new association.
     *
     * <p>
     * {@code synchronized} so two concurrent persists (e.g. a fresh resolveThreadId racing the thread-delete listener)
     * cannot interleave on the shared {@code .tmp} file or race-overwrite each other's snapshot. Each invocation also
     * snapshots {@link #threadBinding} INSIDE the lock so a concurrent {@code remove}/{@code put} after the snapshot is
     * captured is durable on the very next persist call rather than getting silently lost between "old snapshot
     * serialised" and "atomic move publishes".
     * </p>
     *
     * <p>
     * The temp file name embeds a per-call unique suffix so an interrupted previous write (process crash, antivirus
     * lock) cannot corrupt this one — even though the synchronized block already rules out in-process races.
     * </p>
     */
    private synchronized void persistBindings() {
        if (bindingsFile == null) {
            return;
        }
        // Snapshot under the monitor so the JSON we serialise is internally consistent.
        Map<String, String> snapshot = new HashMap<>(threadBinding);
        try {
            Files.createDirectories(bindingsFile.getParent());
            // Unique tmp suffix per write — prevents a stale leftover .tmp from a prior crashed
            // write from being mistaken as ours and silently overwritten mid-move on platforms
            // where ATOMIC_MOVE falls through. Also prevents two unsynchronised callers (e.g.
            // an alternative future caller path) from clobbering the same tmp.
            Path tmp = bindingsFile.resolveSibling(
                bindingsFile.getFileName() + "." + Long.toUnsignedString(System.nanoTime(), 36) + ".tmp");
            try {
                Files.writeString(tmp, BIND_M.writeValueAsString(snapshot), StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, bindingsFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException atomicFail) {
                    Files.move(tmp, bindingsFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                // Best-effort cleanup: if the move succeeded the source is already gone, so this
                // is a no-op; if the write/move threw we don't want a half-written .tmp leaking.
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    /* best effort */ }
            }
        } catch (IOException e) {
            log.warn("GatewayDispatcher persist bindings failed: {}", e.getMessage());
        }
    }

    /**
     * Resolve (and lazily create + persist) the binding from external user identity to an internal thread id.
     */
    private String resolveThreadId(String bindKey) {
        String existing = threadBinding.get(bindKey);
        if (existing != null) {
            return existing;
        }
        String created = UUID.randomUUID().toString();
        String prior = threadBinding.putIfAbsent(bindKey, created);
        if (prior != null) {
            return prior;
        }
        persistBindings();
        return created;
    }

    /**
     * Spring shutdown hook: drain in-flight runs (3s grace) then force-shutdown. Prevents the JVM from hanging on
     * SIGTERM because of non-daemon threads.
     */
    @PreDestroy
    public void shutdown() {
        log.info("GatewayDispatcher shutting down (asyncExecutor + watchdogs)");
        asyncExecutor.shutdown();
        timeouts.shutdownNow();
        try {
            if (!asyncExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                asyncExecutor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            asyncExecutor.shutdownNow();
        }
    }

    /**
     * Dispatch a normalised inbound message synchronously and return the assistant reply. Blocks until the agent loop
     * completes (useful for quick tasks or testing).
     *
     * @param channel short identifier ("dingtalk" / "feishu" / "wecom" / custom)
     * @param user external user id; binds to a sticky thread
     * @param threadKey optional thread/group/conversation key from the platform; null = per-user single thread
     * @param text user message text (already extracted from vendor envelope)
     * @param replyCap max chars to echo back to the channel (full answer still persisted)
     */
    public DispatchResult dispatch(String channel, String user, String threadKey, String text, int replyCap) {
        return dispatch(channel, user, threadKey, null, text, replyCap);
    }

    /**
     * Dispatch overload that accepts a platform-supplied {@code messageId} for stronger idempotency. When non-null, the
     * cache key uses the messageId directly (covers exact redeliveries forever within {@link #IDEMPOTENCY_TTL_MS});
     * when null, falls back to a short-TTL text hash (covers redelivery without swallowing legitimate retypes).
     */
    public DispatchResult dispatch(String channel, String user, String threadKey, String messageId, String text,
        int replyCap) {
        if (text == null || text.isBlank()) {
            return new DispatchResult(null, "", 0L);
        }
        if (!senderAllowed(channel, user)) {
            log.warn("gateway rejected unauthorised sender (channel={} user={})", channel, user);
            return new DispatchResult(null, UNAUTHORIZED_SENDER_REPLY, 0L);
        }
        String bindKey = channel + "|" + (user == null ? "anon" : user) + "|" + (threadKey == null ? "" : threadKey);
        String idemKey = buildIdemKey(bindKey, messageId, text);
        // Short-window idempotency: re-delivery of the SAME message returns the cached reply
        // instead of running the agent twice.
        IdempotentEntry cached = recentReplies.get(idemKey);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAt > now) {
            log.info("gateway dispatch returning cached reply for re-delivery (channel={} user={})", channel, user);
            return new DispatchResult(cached.threadId, cached.reply, 0L);
        }
        sweepIdempotencyCache();
        String threadId = resolveThreadId(bindKey);

        // Single-flight per bindKey: prevents two concurrent IM messages (or a vendor
        // re-delivery firing while the first run is still in progress) from racing on the
        // same ConversationThread — which would interleave t.getMessages().add(...) and
        // loop.run(...), cause duplicate tool side-effects and message-order corruption,
        // and let the second store.save(t) clobber the first. asyncDispatch() already had
        // this guard via userPermits; the sync path was missing it.
        Semaphore permit = userPermits.computeIfAbsent(bindKey, k -> new Semaphore(1));
        if (!permit.tryAcquire()) {
            log.info("gateway sync refused (channel={} user={}): previous run still active", channel, user);
            return new DispatchResult(threadId, PREVIOUS_RUN_ACTIVE_REPLY, 0L);
        }
        BeginResult admitted;
        try {
            admitted = runs.begin(threadId,
                entrySpec(threadId, text, channel, bindKey));
            if (!admitted.started() && isDeletedAdmission(admitted)) {
                String oldId = threadId;
                threadBinding.remove(bindKey, oldId);
                threadId = resolveThreadId(bindKey);
                persistBindings();
                log.info("gateway sync rebinding deleted thread (channel={} user={}): {} -> {}", channel, user,
                    oldId, threadId);
                admitted = runs.begin(threadId,
                    entrySpec(threadId, text, channel, bindKey));
            }
        } catch (RuntimeException admissionFailure) {
            permit.release();
            throw admissionFailure;
        }
        if (!admitted.started()) {
            permit.release();
            log.info("gateway sync refused (channel={} user={} thread={}): {}", channel, user, threadId,
                admitted.rejectCode());
            return new DispatchResult(threadId, THREAD_BUSY_REPLY, 0L);
        }
        RunLease lease = admitted.lease();
        if (archive != null) {
            try { archive.accept(lease.run()); }
            catch (RuntimeException failure) { lease.fail(failure); lease.close(); permit.release(); throw failure; }
        }
        try {
            // `threadId` may have been re-minted just above; capture as final for the lambdas.
            final String activeThreadId = threadId;
            ConversationThread t = store.get(activeThreadId).orElseGet(() -> {
                ConversationThread nt = new ConversationThread();
                nt.setId(activeThreadId);
                // Apply per-channel toolset preset on FIRST run only. Subsequent
                // turns reuse whatever the user / model later set so the operator-chosen
                // default is a starting point, not a permanent override.
                applyChannelDefaults(nt, channel);
                return nt;
            });
            // Capture the assistant boundary BEFORE we append the user msg so we can detect a
            // turn that produced no new assistant message (e.g. provider failed before the first
            // token). Without this we'd echo the previous turn's assistant content back to the IM
            // channel and the user would see a stale answer.
            int beforeMsgCount = t.getMessages().size();
            ChatMessage userMsg = ChatMessage.user(text);
            // Stamp the entry channel + sub-source so the WebUI badge can label this turn
            // as IM-driven instead of human-typed. Pure presentation hint; never sent to LLM.
            userMsg.setChannel(channel);
            userMsg.setSource(user);
            t.getMessages().add(userMsg);

            long t0 = System.currentTimeMillis();
            // AgentLoop swallows most failures and emits them as `error` events instead of
            // throwing, so the outer try/catch catches almost nothing — we MUST observe error
            // events to decide whether the run actually produced output.
            AtomicReference<String> lastErrorMsg = new AtomicReference<>();
            try {
                if (!lease.startExecution()) {
                    return new DispatchResult(activeThreadId, "[agent cancelled]", System.currentTimeMillis() - t0);
                }
                lease.events().accept(StreamEvent.of("run_started",
                    Map.of("origin", new RunOrigin(RunOrigin.Kind.GATEWAY, bindKey).toView())));
                StepResult stepResult = loop.executeStep(
                    new StepRequest(t, executionOptions()),
                    new AgentExecutionContext(lease.runId(), null, ev -> {
                    lease.events().accept(ev);
                    if ("error".equals(ev.getType()) && ev.getData() != null) {
                        Object m = ev.getData().get("message");
                        if (m == null) m = ev.getData().get("reason");
                        if (m != null) lastErrorMsg.set(String.valueOf(m));
                    }
                }, agentExecutions.bind(lease).contextRef(), agentExecutions.bind(lease).onContextReady(), null, null));
                if (stepResult.incomplete()) lastErrorMsg.set(stepResult.detail());
                lease.finishStepResult(stepResult);
            } catch (Exception e) {
                log.error("gateway agent loop failed (channel={} user={})", channel, user, e);
                lease.fail(e);
                store.save(t);
                return new DispatchResult(activeThreadId,
                    "[agent error] " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                    System.currentTimeMillis() - t0);
            }
            store.save(t);
            String reply = newestAssistantSince(t, beforeMsgCount);
            if (reply.isEmpty()) {
                // Fallback: surface a clear error instead of replaying the previous turn's reply.
                String err = lastErrorMsg.get();
                reply = "[agent error] " + (err == null ? "no reply produced" : err);
            }
            if (replyCap > 0 && reply.length() > replyCap) {
                reply = reply.substring(0, replyCap) + "…";
            }
            recentReplies.put(idemKey,
                new IdempotentEntry(reply, activeThreadId, System.currentTimeMillis() + idemTtlFor(messageId)));
            return new DispatchResult(activeThreadId, reply, System.currentTimeMillis() - t0);
        } catch (RuntimeException failure) {
            lease.fail(failure);
            throw failure;
        } finally {
            // Release reservation BEFORE the IM permit so the next gateway message for this user
            // doesn't briefly observe both held (cosmetic; refusal text would otherwise mention
            // "another run" while the reservation is mid-cleanup).
            lease.close();
            permit.release();
        }
    }

    /**
     * Dispatch a normalised inbound message asynchronously. Immediately returns "processing..." placeholder, then runs
     * the agent loop in a background thread. On completion, invokes the replyCallback to push the final result back to
     * the IM platform.
     *
     * @param channel short identifier ("dingtalk" / "feishu" / "wecom" / custom)
     * @param user external user id; binds to a sticky thread
     * @param threadKey optional thread/group/conversation key from the platform
     * @param text user message text
     * @param replyCap max chars to push to the IM channel (full reply is still persisted in the thread). 0 / negative =
     *            no truncation. IM platforms typically reject long messages outright (Feishu/DingTalk/WeCom limits ~2-4
     *            KB) so always set this.
     * @param replyCallback invoked exactly once with (threadId, finalReply) on completion
     * @return immediate placeholder result
     */
    public DispatchResult asyncDispatch(String channel, String user, String threadKey, String text, int replyCap,
        ReplyCallback replyCallback) {
        return asyncDispatch(channel, user, threadKey, null, text, replyCap, replyCallback);
    }

    /** {@link #asyncDispatch} overload accepting a platform messageId for stronger idempotency. */
    public DispatchResult asyncDispatch(String channel, String user, String threadKey, String messageId, String text,
        int replyCap, ReplyCallback replyCallback) {
        return asyncDispatch(channel, user, threadKey, messageId, text, replyCap, replyCallback,
            executionOptions(), null, null, false, null, null);
    }

    /** Authenticated IM transports supply their own sender admission and a concrete reply handler. */
    public DispatchResult dispatchAuthenticated(String channel, String user, String threadKey, String messageId, String text,
        int replyCap, ReplyCallback replyCallback, AgentExecutionOptions options,
        BiConsumer<String, String> onStarted,
        Consumer<StreamEvent> onEvent, TextTarget target, String permissionMode) {
        Objects.requireNonNull(onStarted, "authenticated transport must track the admitted Run");
        Objects.requireNonNull(onEvent, "authenticated transport must handle interactions");
        return asyncDispatch(channel, user, threadKey, messageId, text, replyCap, replyCallback, options, onStarted, onEvent, true, target, permissionMode);
    }

    private DispatchResult asyncDispatch(String channel, String user, String threadKey, String messageId, String text,
        int replyCap, ReplyCallback replyCallback, AgentExecutionOptions options,
        BiConsumer<String, String> onStarted,
        Consumer<StreamEvent> onEvent, boolean senderAdmitted, TextTarget target, String permissionMode) {
        if (text == null || text.isBlank()) {
            return new DispatchResult(null, "", 0L);
        }
        if (!senderAdmitted && !senderAllowed(channel, user)) {
            log.warn("gateway async rejected unauthorised sender (channel={} user={})", channel, user);
            if (replyCallback != null) {
                replyCallback.onComplete(null, UNAUTHORIZED_SENDER_REPLY, 0L);
            }
            return new DispatchResult(null, UNAUTHORIZED_SENDER_REPLY, 0L);
        }

        String bindKey = channel + "|" + (user == null ? "anon" : user) + "|" + (threadKey == null ? "" : threadKey);
        String idemKey = buildIdemKey(bindKey, messageId, text);
        IdempotentEntry cached = recentReplies.get(idemKey);
        long nowMs = System.currentTimeMillis();
        if (cached != null && cached.expiresAt > nowMs) {
            log.info("gateway async returning cached reply for re-delivery (channel={} user={})", channel, user);
            if (replyCallback != null) {
                replyCallback.onComplete(cached.threadId, cached.reply, 0L);
            }
            return new DispatchResult(cached.threadId, cached.reply, 0L);
        }
        sweepIdempotencyCache();
        String threadId = resolveThreadId(bindKey);

        // Refuse the message fast if this user already has a run in flight. We MUST still invoke
        // the callback (with a BUSY-style notice) so the gateway can push the refusal back to the
        // IM group via the outbound webhook — otherwise the user only sees the silent 200 ACK
        // and the second message looks lost.
        Semaphore permit = userPermits.computeIfAbsent(bindKey, k -> new Semaphore(1));
        if (!permit.tryAcquire()) {
            log.info("gateway async refused (channel={} user={}): previous run still active", channel, user);
            if (replyCallback != null) {
                replyCallback.onComplete(threadId, PREVIOUS_RUN_ACTIVE_REPLY, 0L);
            }
            return new DispatchResult(threadId, PREVIOUS_RUN_ACTIVE_REPLY, 0L);
        }
        BeginResult admitted;
        try {
            admitted = runs.begin(threadId,
                entrySpec(threadId, text, channel, bindKey, options, target, permissionMode));
            if (!admitted.started() && isDeletedAdmission(admitted)) {
                String oldId = threadId;
                threadBinding.remove(bindKey, oldId);
                threadId = resolveThreadId(bindKey);
                persistBindings();
                log.info("gateway async rebinding deleted thread (channel={} user={}): {} -> {}", channel, user,
                    oldId, threadId);
                admitted = runs.begin(threadId,
                    entrySpec(threadId, text, channel, bindKey, options, target, permissionMode));
            }
        } catch (RuntimeException admissionFailure) {
            permit.release();
            throw admissionFailure;
        }
        if (!admitted.started()) {
            permit.release();
            log.info("gateway async refused (channel={} user={} thread={}): {}", channel, user, threadId,
                admitted.rejectCode());
            if (replyCallback != null) replyCallback.onComplete(threadId, THREAD_BUSY_REPLY, 0L);
            return new DispatchResult(threadId, THREAD_BUSY_REPLY, 0L);
        }
        // Mark as effectively final for the worker lambdas below — `threadId` itself was
        // (re)assigned just above so it's no longer effectively final from method scope.
        final String activeThreadId = threadId;
        final RunLease lease = admitted.lease();

        final ConversationThread t;
        final int beforeMsgCount;
        final ChatMessage asyncUserMsg;
        try {
            if (archive != null) archive.accept(lease.run());
            if (onStarted != null) onStarted.accept(activeThreadId, lease.runId());
            t = store.get(activeThreadId).orElseGet(() -> {
                ConversationThread nt = new ConversationThread();
                nt.setId(activeThreadId);
                applyChannelDefaults(nt, channel);
                return nt;
            });
            if (senderAdmitted) {
                // Match the frozen submission and its audit record, including when returning to defaults.
                t.setProvider(lease.run().getRequestedProvider());
                t.setModel(lease.run().getRequestedModel());
                t.setReasoningEffort(lease.run().getRequestedReasoningEffort());
                if (t.getState() == null) t.setState(new ThreadState());
                t.getState().setPermissionMode(permissionMode == null ? "ask" : permissionMode);
            }
            // Capture pre-turn assistant boundary — see sync dispatch() for rationale (avoid
            // replaying the previous turn's reply when the current turn produced no new output).
            beforeMsgCount = t.getMessages().size();
            asyncUserMsg = ChatMessage.user(text);
            // B4: badge IM-originated turn so the WebUI can render a channel chip.
            asyncUserMsg.setChannel(channel);
            asyncUserMsg.setSource(user);
            t.getMessages().add(asyncUserMsg);
        } catch (RuntimeException failure) {
            lease.fail(failure);
            lease.close();
            permit.release();
            throw failure;
        }

        // Immediately return placeholder
        DispatchResult placeholder = new DispatchResult(activeThreadId, "⏳ Processing...", 0L);

        // `completed` arbitrates between the worker's normal-finish path and the watchdog's
        // timeout path: whichever flips it first owns the user-visible callback. This prevents
        // a double callback (timeout fires, then the worker eventually returns and we'd push a
        // second contradicting message to the IM user).
        //
        // The single-flight permit, however, is owned EXCLUSIVELY by the worker's finally block.
        // The watchdog deliberately does NOT release it on timeout: the worker may still be
        // running (interrupt is cooperative — AgentLoop only checks at safe points) and if a new
        // request acquired the permit while the old worker was alive, both runs would mutate the
        // same ConversationThread and race on store.save(t), corrupting history. Holding the
        // permit until the worker actually exits trades a bounded "previous run still active"
        // refusal window for correctness.
        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicReference<Thread> workerRef = new AtomicReference<>();
        AtomicReference<ScheduledFuture<?>> timeoutTask = new AtomicReference<>();
        final int cap = replyCap;
        Runnable body = () -> {
            workerRef.set(Thread.currentThread());
            long t0 = System.currentTimeMillis();
            try {
                AtomicReference<String> lastErrorMsg = new AtomicReference<>();
                if (!lease.startExecution()) {
                    if (completed.compareAndSet(false, true) && replyCallback != null) {
                        replyCallback.onComplete(activeThreadId, "[agent cancelled]",
                            System.currentTimeMillis() - t0);
                    }
                    return;
                }
                lease.events().accept(StreamEvent.of("run_started",
                    Map.of("origin", new RunOrigin(RunOrigin.Kind.GATEWAY, bindKey).toView())));
                StepResult stepResult = loop.executeStep(
                    new StepRequest(t, options),
                    new AgentExecutionContext(lease.runId(), null, ev -> {
                    lease.events().accept(ev);
                    if (onEvent != null) onEvent.accept(ev);
                    if ("error".equals(ev.getType())) {
                        log.warn("gateway async agent error (channel={} user={}): {}", channel, user, ev.getData());
                        if (ev.getData() != null) {
                            Object m = ev.getData().get("message");
                            if (m == null) m = ev.getData().get("reason");
                            if (m != null) lastErrorMsg.set(String.valueOf(m));
                        }
                    }
                }, agentExecutions.bind(lease).contextRef(), agentExecutions.bind(lease).onContextReady(), null, null));
                if (stepResult.incomplete()) lastErrorMsg.set(stepResult.detail());
                lease.finishStepResult(stepResult);
                store.save(t);
                String reply = newestAssistantSince(t, beforeMsgCount);
                if (reply.isEmpty()) {
                    String err = lastErrorMsg.get();
                    reply = "[agent error] " + (err == null ? "no reply produced" : err);
                }
                if (onEvent != null && stepResult.incomplete()) {
                    reply += "\n\n任务尚未完成：" + stepResult.detail();
                }
                if (onEvent != null && stepResult.reason() == StepResult.Reason.CANCELLED) {
                    reply = "[agent cancelled]";
                }
                recentReplies.put(idemKey,
                    new IdempotentEntry(reply, activeThreadId, System.currentTimeMillis() + idemTtlFor(messageId)));
                String capped = (cap > 0 && reply.length() > cap) ? reply.substring(0, cap) + "\u2026" : reply;
                if (completed.compareAndSet(false, true)) {
                    if (replyCallback != null) {
                        replyCallback.onComplete(activeThreadId, capped, System.currentTimeMillis() - t0);
                    }
                }
            } catch (Exception e) {
                log.error("gateway async agent loop failed (channel={} user={})", channel, user, e);
                lease.fail(e);
                store.save(t);
                String errorMsg = "[agent error] " + e.getClass().getSimpleName() + ": " + e.getMessage();
                if (completed.compareAndSet(false, true)) {
                    if (replyCallback != null) {
                        replyCallback.onComplete(activeThreadId, errorMsg, System.currentTimeMillis() - t0);
                    }
                }
            } finally {
                ScheduledFuture<?> timeout = timeoutTask.get();
                if (timeout != null) timeout.cancel(false);
                workerRef.set(null);
                // Release reservation BEFORE the IM permit so the next message for this user
                // doesn't briefly observe both held. Single owner — the watchdog never
                // releases either, so the worker's actual exit is the only release point.
                lease.close();
                // Always release exactly once when the worker actually exits. The watchdog never
                // releases, so this is the single owner of the permit lifecycle.
                permit.release();
            }
        };
        // Wall-clock guard: if the worker is still alive after timeout, interrupt it. AgentLoop
        // checks ctx.isCancelled()/Thread.interrupted() at safe points and unwinds gracefully.
        final long timeoutMs = senderAdmitted ? 30L * 60_000 : ASYNC_DISPATCH_TIMEOUT_MS;
        Runnable watchdog = () -> {
                if (!completed.compareAndSet(false, true)) {
                    return; // worker already finished — nothing to do
                }
                Thread w = workerRef.get();
                if (w != null && w.isAlive()) {
                    log.warn("gateway async dispatch timeout ({}ms) \u2014 interrupting (channel={} user={})",
                        timeoutMs, channel, user);
                    w.interrupt();
                }
                lease.cancel();
                if (replyCallback != null) {
                    replyCallback.onComplete(activeThreadId,
                        "[agent timeout] task exceeded " + (timeoutMs / 1000) + "s",
                        timeoutMs);
                }
                // Do NOT release the permit here. The worker's finally block is the single owner;
                // releasing here would let a new request begin while the old worker is still
                // executing AgentLoop (interrupt is cooperative) and racing on store.save(t).
        };
        try {
            timeoutTask.set(timeouts.schedule(watchdog, timeoutMs, TimeUnit.MILLISECONDS));
            asyncExecutor.execute(body);
        } catch (RejectedExecutionException ree) {
            ScheduledFuture<?> timeout = timeoutTask.get();
            if (timeout != null) timeout.cancel(false);
            // Bounded queue full — release reservations and surface a busy reply via the
            // callback so the IM platform doesn't keep redelivering against a saturated
            // backend (which would only worsen the pile-up).
            log.warn("gateway async rejected (channel={} user={}): executor saturated", channel, user);
            // Roll back the user message we eagerly appended at line ~788. Without this,
            // the unrun input lives on in {@code t.getMessages()} (FtsSessionStore returns the
            // SAME cached object on subsequent get()s) and the next successful turn's
            // store.save(t) silently persists a phantom user line into history. Identity-based
            // remove is precise — we hold the exact reference.
            t.getMessages().remove(asyncUserMsg);
            lease.fail(ree);
            lease.close();
            permit.release();
            if (replyCallback != null) {
                replyCallback.onComplete(activeThreadId, BUSY_REPLY, 0L);
            }
            return new DispatchResult(activeThreadId, BUSY_REPLY, 0L);
        }
        return placeholder;
    }

    /**
     * Back-compat overload: delegates with no reply truncation. Prefer the {@code replyCap} variant for IM channels
     * with strict size limits.
     */
    public DispatchResult asyncDispatch(String channel, String user, String threadKey, String text,
        ReplyCallback replyCallback) {
        return asyncDispatch(channel, user, threadKey, text, 0, replyCallback);
    }

    /**
     * Callback interface for async dispatch completion. Implement this to push the final agent reply back to the IM
     * platform.
     */
    @FunctionalInterface
    public interface ReplyCallback {
        /**
         * Called when the async agent loop completes.
         *
         * @param threadId the conversation thread id
         * @param reply the final assistant reply (full text, not truncated)
         * @param durationMs total execution time in milliseconds
         */
        void onComplete(String threadId, String reply, long durationMs);
    }

    @SuppressWarnings("unused") // kept for tests / future callers; prefer newestAssistantSince in this class
    private static String lastAssistant(ConversationThread t) {
        for (int i = t.getMessages().size() - 1; i >= 0; i--) {
            ChatMessage m = t.getMessages().get(i);
            if ("assistant".equals(m.getRole()) && m.getContent() != null) {
                return m.getContent();
            }
        }
        return "";
    }

    /**
     * Search backwards but stop at {@code beforeMsgCount} so we ONLY return an assistant message produced by the
     * current turn. Returns "" if the loop exited without appending any new assistant text — e.g. provider failed
     * before the first token. The caller is then expected to surface a typed error to the IM channel rather than
     * echoing the previous turn's reply.
     */
    private static String newestAssistantSince(ConversationThread t, int beforeMsgCount) {
        int from = Math.max(beforeMsgCount, 0);
        for (int i = t.getMessages().size() - 1; i >= from; i--) {
            ChatMessage m = t.getMessages().get(i);
            if ("assistant".equals(m.getRole()) && m.getContent() != null && !m.getContent().isBlank()) {
                return m.getContent();
            }
        }
        return "";
    }

    private static boolean isDeletedAdmission(BeginResult result) {
        return "thread_deleted".equals(result.rejectCode())
            || "thread_deleted_persisted".equals(result.rejectCode());
    }

    public record DispatchResult(String threadId, String reply, long durationMs) {
    }
}
