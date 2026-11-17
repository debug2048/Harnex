package com.harnex.provider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import com.harnex.model.StreamEvent;
import com.harnex.net.HttpExchangeState;
import com.harnex.net.HttpExchangeState.Acceptance;

/** Mutable only for the duration of one call. Publication uses immutable, monotonically revised snapshots. */
final class ModelCallTrace {
    private final String id = "call_" + UUID.randomUUID().toString().replace("-", "");
    private final long createdAt = System.currentTimeMillis();
    private final ProviderCallScope.Context owner = ProviderCallScope.current();
    private final ProviderCallScope.QueueTiming queue = ProviderCallScope.takeQueueTiming();
    private final String purpose;
    private final String requestedProvider;
    private final String requestedModel;
    private final Consumer<StreamEvent> events;
    private final Consumer<ModelCallRecord> records;
    private final List<ModelCallRecord.Attempt> attempts = new ArrayList<>();
    private long revision;
    private ModelCallRecord.State state = ModelCallRecord.State.RUNNING;
    private Long finishedAt;

    ModelCallTrace(String requestedProvider, String requestedModel, String purpose, Consumer<StreamEvent> events,
        Consumer<ModelCallRecord> records) {
        this.requestedProvider = requestedProvider; this.requestedModel = requestedModel;
        this.purpose = purpose == null || purpose.isBlank() ? owner == null ? "conversation" : owner.purpose() : purpose;
        this.events = events; this.records = records;
        publish();
    }

    synchronized void beginAttempt(String provider, String model, String protocol) {
        int index = attempts.size() + 1;
        Map<String,Long> timings = new LinkedHashMap<>();
        if (index == 1 && queue != null) { timings.put("queuedAt",queue.queuedAt()); timings.put("workerStartedAt",queue.workerStartedAt()); timings.put("queueMillis",queue.queueMillis()); }
        attempts.add(new ModelCallRecord.Attempt(id + "_" + index, index, provider, model, protocol,
            System.currentTimeMillis(), null, null, ModelCallRecord.State.RUNNING, Acceptance.NOT_SENT, null, null, 0, null, null, timings));
        publish();
    }

    synchronized void event(StreamEvent event) {
        if (!attempts.isEmpty()) {
            ModelCallRecord.Attempt previous = attempts.get(attempts.size() - 1);
            Acceptance acceptance = previous.acceptance();
            Long firstOutput = previous.firstOutputAfterMillis();
            ProviderTokenUsage usage = previous.usage();
            if ("message_start".equals(event.getType())) acceptance = Acceptance.ACCEPTED;
            if (List.of("text_delta", "thinking_delta", "message_end", "tool_call_delta").contains(event.getType())) {
                acceptance = Acceptance.OUTPUT_RECEIVED;
                if (firstOutput == null) firstOutput = Math.max(0, System.currentTimeMillis() - previous.startedAt());
            }
            if ("usage".equals(event.getType())) {
                Map<String, Object> data = event.getData();
                usage = new ProviderTokenUsage(number(data, "prompt"), number(data, "completion"), number(data, "cachedInput"),
                    number(data, "cacheWriteInput"), number(data, "reasoning"));
            }
            attempts.set(attempts.size() - 1, new ModelCallRecord.Attempt(previous.attemptId(), previous.index(),
                previous.provider(), previous.model(), previous.protocol(), previous.startedAt(), previous.finishedAt(),
                firstOutput, previous.state(), acceptance, previous.upstreamRequestId(), previous.errorCode(), previous.retryAfterMillis(), usage, previous.contextPlanId(), previous.timings()));
        }
        if ("usage".equals(event.getType())) {
            publish();
            Map<String, Object> data = new LinkedHashMap<>(event.getData());
            data.put("callId", id);
            if (!attempts.isEmpty()) data.put("attemptId", attempts.get(attempts.size() - 1).attemptId());
            events.accept(StreamEvent.of(event.getType(), data));
        } else events.accept(event);
    }

    synchronized void finishAttempt(ProviderException failure) {
        if (attempts.isEmpty()) return;
        ModelCallRecord.Attempt previous = attempts.get(attempts.size() - 1);
        Acceptance acceptance = failure == null ? Acceptance.OUTPUT_RECEIVED : failure.acceptance;
        if (acceptance == Acceptance.UNKNOWN && previous.acceptance() != Acceptance.NOT_SENT) acceptance = previous.acceptance();
        ModelCallRecord.State attemptState = failure == null ? ModelCallRecord.State.SUCCEEDED
            : "cancelled".equals(failure.code) ? ModelCallRecord.State.CANCELLED
            : failure.safeToRetry() ? ModelCallRecord.State.FAILED : ModelCallRecord.State.UNKNOWN;
        attempts.set(attempts.size() - 1, new ModelCallRecord.Attempt(previous.attemptId(), previous.index(),
            previous.provider(), previous.model(), previous.protocol(), previous.startedAt(), System.currentTimeMillis(),
            previous.firstOutputAfterMillis(), attemptState, acceptance, failure == null || failure.upstreamRequestId == null
                ? previous.upstreamRequestId() : failure.upstreamRequestId,
            failure == null ? null : failure.code, failure == null ? 0 : failure.retryAfterMillis,
            failure != null && failure.usage != null ? failure.usage : previous.usage(), previous.contextPlanId(), previous.timings()));
        publish();
    }

    synchronized void dispatching() {
        if (attempts.isEmpty()) return;
        ModelCallRecord.Attempt previous = attempts.get(attempts.size() - 1);
        Map<String,Long> timings = new LinkedHashMap<>(previous.timings()); timings.put("dispatchedAt",System.currentTimeMillis());
        attempts.set(attempts.size() - 1, new ModelCallRecord.Attempt(previous.attemptId(), previous.index(),
            previous.provider(), previous.model(), previous.protocol(), previous.startedAt(), previous.finishedAt(),
            previous.firstOutputAfterMillis(), previous.state(), Acceptance.SENT_UNKNOWN, previous.upstreamRequestId(),
            previous.errorCode(), previous.retryAfterMillis(), previous.usage(), previous.contextPlanId(), timings));
        publish();
    }

    synchronized void finish(RuntimeException failure) {
        state = failure == null ? ModelCallRecord.State.SUCCEEDED
            : failure instanceof ProviderException pe && "cancelled".equals(pe.code) ? ModelCallRecord.State.CANCELLED
            : failure instanceof ProviderException pe && !pe.safeToRetry() ? ModelCallRecord.State.UNKNOWN : ModelCallRecord.State.FAILED;
        finishedAt = System.currentTimeMillis();
        publish();
    }

    synchronized void exchange(HttpExchangeState exchange) {
        if (attempts.isEmpty()) return;
        ModelCallRecord.Attempt previous = attempts.get(attempts.size() - 1);
        Map<String,Long> timings = new LinkedHashMap<>(previous.timings()); timings.putAll(exchange.timings());
        attempts.set(attempts.size() - 1, new ModelCallRecord.Attempt(previous.attemptId(), previous.index(),
            previous.provider(), previous.model(), previous.protocol(), previous.startedAt(), previous.finishedAt(),
            previous.firstOutputAfterMillis(), previous.state(), exchange.acceptance(), exchange.requestId(),
            previous.errorCode(), exchange.retryAfterMillis(), previous.usage(), previous.contextPlanId(), timings));
    }

    synchronized void usage(ProviderTokenUsage usage) {
        if (attempts.isEmpty()) return;
        ModelCallRecord.Attempt previous = attempts.get(attempts.size() - 1);
        attempts.set(attempts.size() - 1, new ModelCallRecord.Attempt(previous.attemptId(), previous.index(),
            previous.provider(), previous.model(), previous.protocol(), previous.startedAt(), previous.finishedAt(),
            previous.firstOutputAfterMillis(), previous.state(), previous.acceptance(), previous.upstreamRequestId(),
            previous.errorCode(), previous.retryAfterMillis(), usage, previous.contextPlanId(), previous.timings()));
    }
    synchronized ProviderCallScope.CallBinding callScope() { return ProviderCallScope.bindCall(id,attempts.get(attempts.size()-1).attemptId()); }
    synchronized void contextPlan(String contextPlanId) {
        var previous = attempts.get(attempts.size()-1);
        attempts.set(attempts.size()-1,new ModelCallRecord.Attempt(previous.attemptId(),previous.index(),previous.provider(),previous.model(),previous.protocol(),
            previous.startedAt(),previous.finishedAt(),previous.firstOutputAfterMillis(),previous.state(),previous.acceptance(),previous.upstreamRequestId(),previous.errorCode(),
            previous.retryAfterMillis(),previous.usage(),contextPlanId,previous.timings()));
    }

    private void publish() {
        boolean complete = !attempts.isEmpty() && attempts.stream().allMatch(attempt -> attempt.usage() != null
            || attempt.acceptance() == Acceptance.NOT_SENT || attempt.acceptance() == Acceptance.REJECTED);
        ModelCallRecord record = new ModelCallRecord(1, id, owner == null ? null : owner.runId(), owner == null ? null : owner.threadId(),
            owner == null ? null : owner.stepId(), purpose, requestedProvider, requestedModel, state, ++revision, createdAt,
            finishedAt, List.copyOf(attempts), complete);
        // Cancellation must still close the durable attempt. NIO rejects writes made with an interrupt flag;
        // shield only terminal publication, then restore the flag for the caller's cancellation semantics.
        boolean terminal = finishedAt != null || !attempts.isEmpty() && attempts.get(attempts.size() - 1).finishedAt() != null;
        boolean interrupted = terminal && Thread.interrupted();
        try {
            try { records.accept(record); }
            catch (RuntimeException failure) {
                if (!terminal || !interruptedWrite(failure)) throw failure;
                interrupted |= Thread.interrupted();
                records.accept(record); // Same immutable revision: safe if cancellation raced the first write.
            }
            events.accept(StreamEvent.of("model_call_updated", Map.of("callId", record.callId(), "revision", record.revision(), "call", record)));
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    private static boolean interruptedWrite(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof java.nio.channels.ClosedByInterruptException) return true;
        return false;
    }
    private static long number(Map<String, Object> data, String key) { Object value = data.get(key); return value instanceof Number n ? n.longValue() : 0; }
}
