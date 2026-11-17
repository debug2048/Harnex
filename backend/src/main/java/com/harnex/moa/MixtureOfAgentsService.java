package com.harnex.moa;

import java.util.List;
import java.util.concurrent.*;

import org.springframework.stereotype.Service;

import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.model.ChatMessage;
import com.harnex.provider.OneShotResult;
import com.harnex.provider.ProviderCallScope;
import com.harnex.provider.ProviderRouter;
import com.harnex.provider.ProviderTokenUsage;

import jakarta.annotation.PreDestroy;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * Mixture-of-Agents (Hermes Agent tools/mixture_of_agents_tool.py), Java port.
 */
@Slf4j
@Service
@ProjectRuntime
public class MixtureOfAgentsService {

    /**
     * Sized to fan out every configured reference model in parallel. A fixed pool of 4 (the previous default) caused
     * N>4 references to queue, so the slowest expert no longer determined wall-clock latency — instead, the (N-4)th
     * call started after the first 4 returned. We size at construction to {@code max(4, configured refs)} so the
     * configured fanout is honoured without runtime growth.
     */
    private final ExecutorService pool;
    /**
     * Dedicated single-thread executor for the aggregator (synthesiser) call. Previously the aggregator submitted onto
     * the same fixed-size {@link #pool} as the references; if all reference threads were stuck (slow upstream that
     * hadn't yet hit our cancellation deadline), the aggregator would queue behind them and the user would observe the
     * combined ref-timeout + agg-timeout instead of just agg-timeout. A separate thread also means the aggregator never
     * has to wait for an unrelated burst of {@code /api/moa} calls to drain.
     */
    private final ExecutorService aggregatorPool;
    private final ProviderRouter router;
    private final AgentConfig cfg;
    /**
     * Batch-level concurrency gate. The previous "bounded queue" defence was insufficient: when a second
     * {@link #invoke} call arrived during the first call's wall-clock window, ref-task submissions could still queue
     * (queue capacity == fanout size meant the FIRST batch's tasks filled the queue, but a second invoke that
     * overlapped on a half-drained pool would slot in behind them) and — worse — if the caller's CompletableFuture
     * timed out, the queued supplier would still fire later and burn provider tokens for an answer nobody would read. A
     * 1-permit semaphore makes the whole {@code invoke} atomic-from-the-pool's-perspective. tryAcquire() failure throws
     * {@link MoaBusyException} which the controller maps to 503.
     */
    private final Semaphore batchPermit = new Semaphore(1);

    /** Thrown when MoA is already running a batch — caller should retry shortly. */
    public static class MoaBusyException extends RuntimeException {
        public MoaBusyException() {
            super("MoA is busy serving another batch; retry shortly");
        }
    }

    public MixtureOfAgentsService(ProviderRouter router, AgentConfig cfg) {
        this.router = router;
        this.cfg = cfg;
        int refs = cfg.getMoa() != null && cfg.getMoa().getReferenceModels() != null
            ? cfg.getMoa().getReferenceModels().size() : 0;
        int size = Math.max(4, refs);
        // SynchronousQueue + AbortPolicy: every submit() must hand directly to a worker; if all
        // workers are busy, the executor REJECTS rather than queueing. The previous
        // ArrayBlockingQueue(size) re-introduced the very failure mode this fix was meant to
        // close: when a provider lacked a real timeout overload, orTimeout() would only mark
        // the caller's CompletableFuture done — the worker thread kept running, the {@link
        // #batchPermit} got released after the join, and the NEXT batch's tasks happily
        // queued behind the still-executing zombie tasks. Hours later those zombies could
        // still emit answers nobody would ever read. SynchronousQueue makes "queue is full
        // because workers are busy" indistinguishable from "no slot available right now"
        // and AbortPolicy converts that to a structured rejection.
        ThreadPoolExecutor refExec =
            new ThreadPoolExecutor(size, size, 0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), r -> {
                Thread t = new Thread(r, "moa-worker");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.AbortPolicy());
        this.pool = refExec;
        // Aggregator pool: single thread, SynchronousQueue. Same reasoning — a wedged
        // synthesiser whose worker hasn't returned yet must NOT silently accept a queued
        // second submission.
        ThreadPoolExecutor aggExec =
            new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), r -> {
                Thread t = new Thread(r, "moa-aggregator");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.AbortPolicy());
        this.aggregatorPool = aggExec;
    }

    @PreDestroy
    public void close() {
        pool.shutdownNow();
        aggregatorPool.shutdownNow();
        try {
            if (!pool.awaitTermination(3, TimeUnit.SECONDS)) {
                log.warn("MoA pool did not terminate within 3s");
            }
            if (!aggregatorPool.awaitTermination(3, TimeUnit.SECONDS)) {
                log.warn("MoA aggregator pool did not terminate within 3s");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** Per-reference-model timeout. Stops one slow expert from hanging the whole vote. */
    private static final long REF_TIMEOUT_MS = 90_000L;
    /**
     * Aggregator timeout. The synthesiser tends to be slower than reference experts (longer prompt), but must still
     * finish within an envelope the SSE client / NFR can tolerate.
     */
    private static final long AGG_TIMEOUT_MS = 120_000L;

    public MoAResult invoke(String userPrompt) {
        if (!cfg.getMoa().isEnabled()) {
            throw new IllegalStateException("MoA disabled; set harnex.moa.enabled=true");
        }
        List<String> refs = cfg.getMoa().getReferenceModels();
        String aggregator = cfg.getMoa().getAggregatorModel();
        if (refs == null || refs.isEmpty() || aggregator == null) {
            throw new IllegalStateException("MoA not fully configured");
        }
        // Batch gate: only one MoA batch may run at a time. Without this, a second concurrent
        // /api/moa call would race with the first on the SynchronousQueue (rejected by the
        // pool, but the caller would then see a generic CompletionException rather than a
        // structured "busy" signal). Failing fast at the front door also means no provider
        // tokens are burned for a request whose response we'd never deliver.
        if (!batchPermit.tryAcquire()) {
            throw new MoaBusyException();
        }
        try {
            return invokeLocked(userPrompt, refs, aggregator);
        } finally {
            batchPermit.release();
        }
    }

    private MoAResult invokeLocked(String userPrompt, List<String> refs, String aggregator) {
        long t0 = System.currentTimeMillis();
        ConcurrentLinkedQueue<ProviderTokenUsage> reportedUsage = new ConcurrentLinkedQueue<>();
        List<CompletableFuture<String>> futs = refs.stream().map(m -> {
            // Wrap submission so a SynchronousQueue rejection (workers from a previous batch
            // still running because their provider didn't honour cancel — see pool comment)
            // becomes a per-reference error string instead of a synchronous throw that would
            // break the whole batch and bubble a 500 to the controller. The batch then
            // proceeds with whichever submissions DID get a worker; if too many were
            // rejected, the existing "ok <= 1" path returns a degraded result.
            try {
                return CompletableFuture.supplyAsync(ProviderCallScope.captureSupplier(() -> {
                    try {
                        // Provider-qualified references prevent a model id from ever being sent to an unrelated
                        // endpoint. Push the
                        // wall-clock budget DOWN into the provider so {@code Call.cancel()}
                        // actually frees the worker on slow upstreams. The previous
                        // {@code orTimeout()} only marked the future done and left the OkHttp
                        // call attached to the worker until the 10-min idle readTimeout.
                        int slash = m.indexOf('/');
                        if (slash <= 0 || slash == m.length() - 1) {
                            throw new IllegalArgumentException("MoA reference must use provider/model: " + m);
                        }
                        String provider = m.substring(0, slash);
                        String model = m.substring(slash + 1);
                        OneShotResult result = router.oneShotOn(provider, model,
                            List.of(ChatMessage.user(userPrompt)), 0.6f,
                            REF_TIMEOUT_MS);
                        if (result != null && result.usage() != null) reportedUsage.add(result.usage());
                        String out = result == null ? null : result.content();
                        return out == null ? "[error: provider returned null for " + m + "]" : out;
                    } catch (Exception e) {
                        log.warn("MoA ref {} failed: {}", m, e.getMessage());
                        return "[error: " + e.getMessage() + "]";
                    }
                }), pool)
                    // orTimeout is now a SAFETY NET on top of the provider-level cancel: if a
                    // custom ModelProvider implementation hasn't been updated to honour the
                    // timeout overload, we still bound the future. Add a small grace so the
                    // provider's own cancel-then-throw path always wins the race and produces
                    // the better "[error: timeout after Xms]" message instead of a generic
                    // CompletionException.
                    .orTimeout(REF_TIMEOUT_MS + 5_000L, TimeUnit.MILLISECONDS)
                    .exceptionally(ex -> "[error: "
                        + (ex instanceof TimeoutException ? "timeout after " + REF_TIMEOUT_MS + "ms" : ex.getMessage())
                        + "]");
            } catch (RejectedExecutionException ree) {
                log.warn("MoA ref {} rejected: pool saturated by previous batch's zombie workers", m);
                return CompletableFuture
                    .completedFuture("[error: pool saturated; previous batch's worker still running for " + m + "]");
            }
        }).toList();
        List<String> refOut = futs.stream().map(CompletableFuture::join).toList();

        int ok = (int)refOut.stream().filter(s -> !s.startsWith("[error")).count();
        if (ok <= 1) {
            String reason = ok == 1 ? "only_one_reference_succeeded" : "all_references_failed";
            return new MoAResult(refOut, ok == 1
                ? refOut.stream().filter(s -> !s.startsWith("[error")).findFirst().get() : "[all references failed]",
                aggregator, System.currentTimeMillis() - t0, true, reason, sumUsage(reportedUsage),
                ok == refs.size() && reportedUsage.size() == refs.size());
        }
        String aggPrompt = buildAggPrompt(userPrompt, refOut);
        // Aggregator may also be specified as "provider/model" (e.g. "openai/gpt-4o-mini") so the
        // synthesiser can run on a different provider than the references — useful when one
        // provider is the cheapest reasoning model but a different one has the strongest synthesis.
        int aggSlash = aggregator.indexOf('/');
        String finalOut = null;
        List<ChatMessage> aggMsgs =
            List.of(ChatMessage.system("You are a careful synthesiser. Combine expert answers into one best answer."),
                ChatMessage.user(aggPrompt));
        // Run the aggregator on the same pool with an explicit wall-clock budget so a wedged
        // synthesiser cannot strand the SSE caller until the global SSE timeout fires (which
        // would burn many minutes of UX). On timeout we fall through to the same first-successful-
        // reference fallback that already covers null/exception below.
        boolean aggregatorUsageReported = false;
        int referenceUsageReports = reportedUsage.size();
        try {
            final String aggFinal = aggregator;
            final int aggSlashFinal = aggSlash;
            // Submit on the dedicated aggregatorPool so a saturated reference pool cannot
            // delay the synthesis. Push the wall-clock budget into the provider so
            // {@code Call.cancel()} actually frees the worker on a wedged synthesiser.
            CompletableFuture<OneShotResult> aggFut = CompletableFuture.supplyAsync(ProviderCallScope.captureSupplier(() -> {
                if (aggSlashFinal <= 0 || aggSlashFinal == aggFinal.length() - 1) {
                    throw new IllegalArgumentException("MoA aggregator must use provider/model: " + aggFinal);
                }
                return router.oneShotOn(aggFinal.substring(0, aggSlashFinal), aggFinal.substring(aggSlashFinal + 1),
                    aggMsgs, 0.4f, AGG_TIMEOUT_MS);
            }), aggregatorPool).orTimeout(AGG_TIMEOUT_MS + 5_000L, TimeUnit.MILLISECONDS);
            OneShotResult aggregateResult = aggFut.join();
            if (aggregateResult != null && aggregateResult.usage() != null) {
                reportedUsage.add(aggregateResult.usage());
                aggregatorUsageReported = true;
            }
            finalOut = aggregateResult == null ? null : aggregateResult.content();
        } catch (Exception e) {
            log.warn("MoA aggregator '{}' failed: {} \u2014 falling back to first successful reference output",
                aggregator, e.getMessage());
        }
        // Fallback when aggregator returned null OR threw: don't strand the caller with a null reply
        // after we already burned tokens on N reference calls. Return the first successful ref output
        // (which is usually still useful) and prefix it so the caller can detect the degradation.
        boolean aggregatorDegraded = false;
        String degradationReason = null;
        if (finalOut == null || finalOut.isBlank()) {
            String firstOk = refOut.stream().filter(s -> !s.startsWith("[error")).findFirst().orElse(null);
            if (firstOk != null) {
                finalOut = "[aggregator failed; showing first reference output]\n\n" + firstOk;
                aggregatorDegraded = true;
                degradationReason = "aggregator_failed_fallback_to_reference";
            } else {
                finalOut = "[aggregator failed and all references failed]";
                aggregatorDegraded = true;
                degradationReason = "aggregator_and_all_references_failed";
            }
        }
        return new MoAResult(refOut, finalOut, aggregator, System.currentTimeMillis() - t0, aggregatorDegraded,
            degradationReason, sumUsage(reportedUsage), !aggregatorDegraded && ok == refs.size()
                && referenceUsageReports == refs.size() && aggregatorUsageReported);
    }

    private static ProviderTokenUsage sumUsage(Iterable<ProviderTokenUsage> usages) {
        long prompt = 0;
        long completion = 0;
        long cachedInput = 0;
        long cacheWriteInput = 0;
        long reasoning = 0;
        for (ProviderTokenUsage usage : usages) {
            if (usage == null) continue;
            prompt += usage.prompt();
            completion += usage.completion();
            cachedInput += usage.cachedInput();
            cacheWriteInput += usage.cacheWriteInput();
            reasoning += usage.reasoning();
        }
        return new ProviderTokenUsage(prompt, completion, cachedInput, cacheWriteInput, reasoning);
    }

    private static String buildAggPrompt(String user, List<String> refs) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are synthesising the final answer. Below are ").append(refs.size())
            .append(" expert answers to the same user query. Produce the single best answer:\n")
            .append("- Remove contradictions; keep unique insights.\n")
            .append("- If experts disagree meaningfully, briefly note the disagreement.\n\n").append("User query:\n")
            .append(user).append("\n\nExpert answers:\n");
        for (int i = 0; i < refs.size(); i++) {
            sb.append("[#").append(i + 1).append("]\n").append(refs.get(i)).append("\n\n");
        }
        return sb.toString();
    }

    @Data
    @AllArgsConstructor
    public static class MoAResult {
        private List<String> referenceOutputs;
        private String aggregated;
        private String aggregator;
        private long durationMs;
        /**
         * True when the result was produced via a degraded path (≤1 reference succeeded, OR the aggregator failed and
         * we fell back to a reference). UI / observability surfaces can render a "MoA ran in degraded mode" indicator
         * instead of the user wondering why the answer reads like a single model rather than a synthesis.
         */
        private boolean degraded;
        /** Human-readable reason when {@link #degraded} is true. */
        private String degradationReason;
        /** Sum of provider-reported reference and aggregator usage; zero when upstream omitted usage. */
        private ProviderTokenUsage usage;
        /** True only when every reference and the aggregator returned provider usage. */
        private boolean usageComplete;

    }
}
