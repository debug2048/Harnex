package com.harnex.run;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.harnex.interaction.InteractionStateBoundary;
import com.harnex.model.StreamEvent;

import io.swagger.v3.oas.annotations.media.Schema;

/** Transport-neutral sequencing, replay buffering and subscriber fan-out for root run events. */
@Component
public class RunEventBus implements InteractionStateBoundary {

    private static final int EVENT_BUFFER_MAX = 1000;
    private static final int SEQUENCE_LEASE_SIZE = 128;
    private static final Set<String> PERSISTED_EVENT_TYPES = Set.of("run_started", "message_start", "utility_model_start",
        "provider_switched", "usage", "tool_use_start", "tool_use_end", "subagent_started",
        "subagent_finished", "approval_required", "user_question", "plan_review", "goal_continue", "done",
        "context_compression_started", "context_compression_finished", "run_control_checkpoint",
        "tool_calls_superseded", "run_input_applying", "run_input_applied", "run_input_rejected",
        "run_input_followup_started", "interactions_superseded", "cancelled", "error");

    private final RunRegistry registry;
    private final RunStreamCursorStore cursors;
    private final RunToolStore tools;
    private final RunProcessStore processes;
    private final ConcurrentMap<String, Channel> channels = new ConcurrentHashMap<>();

    @Autowired
    public RunEventBus(RunRegistry registry, RunStreamCursorStore cursors, RunToolStore tools, RunProcessStore processes) {
        this.registry = registry;
        this.cursors = cursors;
        this.tools = tools;
        this.processes = processes;
        registry.liveRuns().stream().filter(RunHandle::isRecovered).forEach(this::attachRecovered);
    }

    /** In-memory cursor constructor for focused unit tests and non-Spring adapter fixtures. */
    public RunEventBus(RunRegistry registry) {
        this(registry, RunStreamCursorStore.inMemory());
    }
    public RunEventBus(RunRegistry registry, RunStreamCursorStore cursors) {
        this(registry, cursors, RunToolStore.inMemory());
    }
    public RunEventBus(RunRegistry registry, RunStreamCursorStore cursors, RunToolStore tools) {
        this(registry, cursors, tools, RunProcessStore.inMemory());
    }

    void attach(RunHandle run) {
        RunStreamCursorRecord cursor = cursors.open(run.getRunId(), run.getLastSeq());
        channels.putIfAbsent(run.getRunId(), new Channel(cursor));
    }

    private void attachRecovered(RunHandle run) {
        RunStreamCursorRecord cursor = cursors.rehydrate(run.getRunId(), run.getLastSeq());
        channels.putIfAbsent(run.getRunId(), new Channel(cursor));
    }

    public void publish(RunHandle run, StreamEvent event) {
        if (run == null || event == null) return;
        Channel channel = channels.computeIfAbsent(run.getRunId(), ignored ->
            new Channel(cursors.open(run.getRunId(), run.getLastSeq())));
        boolean drain;
        synchronized (channel) {
            append(run, channel, event);
            drain = startDrain(channel);
        }
        if (drain) drain(channel);
        if (isPersistedEvent(event.getType())) registry.persist(run);
    }

    private void append(RunHandle run, Channel channel, StreamEvent event) {
        long seq = channel.nextSequence(cursors, run.getRunId());
        ActivityProjector.enrich(event); event.setRunId(run.getRunId()); event.setThreadId(run.getThreadId());
        event.setSeq(seq); event.setStreamEpoch(channel.streamEpoch); event.setTs(System.currentTimeMillis());
        run.recordPublishedSeq(seq); RunFactProjector.project(run, event);
        var tool = channel.presentation.apply(event);
        if (tool != null) tools.capture(run.getRunId(), tool, event);
        var progress = channel.presentation.progressMessage();
        if (progress != null) processes.save(run.getRunId(), progress);
        channel.buffer.addLast(event);
        while (channel.buffer.size() > EVENT_BUFFER_MAX) { channel.buffer.pollFirst(); channel.firstBufferedSeq++; }
        channel.deliveries.addLast(new Delivery(event, List.copyOf(channel.listeners), false));
    }
    private static boolean startDrain(Channel channel) {
        if (channel.draining || channel.mutationDepth > 0 || channel.deliveries.isEmpty()) return false;
        channel.draining = true; return true;
    }

    /** Registers/settles pending interactions at the same ordered boundary used for snapshot capture. */
    @Override public <T> T update(String conversationId, String kind, Supplier<T> mutation) {
        RunHandle run = registry.get(registry.openRunId(conversationId));
        if (run == null) return mutation.get();
        Channel channel = channels.computeIfAbsent(run.getRunId(), ignored -> new Channel(cursors.open(run.getRunId(), run.getLastSeq())));
        boolean drain = false;
        try {
            synchronized (channel) {
                channel.mutationDepth++;
                try { return mutation.get(); }
                finally {
                    try { append(run, channel, StreamEvent.of("interaction_updated", Map.of("kind", kind))); }
                    finally { channel.mutationDepth--; drain = startDrain(channel); }
                }
            }
        } finally { if (drain) drain(channel); }
    }

    /** Capture references and live display under the event lock; never perform network work in the reader. */
    public <T> T capture(RunHandle run, BiFunction<PresentationCursor, RunPresentationState.Snapshot, T> reader) {
        Channel channel = channels.get(run.getRunId());
        if (channel == null) return reader.apply(new PresentationCursor(null, run.getLastSeq(), false), RunPresentationState.empty());
        synchronized (channel) {
            return reader.apply(new PresentationCursor(channel.streamEpoch, channel.nextSequence - 1, !channel.expired), channel.presentation.snapshot());
        }
    }
    @Schema(requiredProperties = {"projectionSeq", "replayAvailable"})
    public record PresentationCursor(Long streamEpoch, long projectionSeq, boolean replayAvailable) {}

    public Subscription subscribe(RunHandle run, long afterSeq, Listener listener) {
        return subscribe(run, afterSeq, null, listener);
    }

    public Subscription subscribe(RunHandle run, long afterSeq, Long expectedStreamEpoch, Listener listener) {
        if (run == null) throw new IllegalArgumentException("run is required");
        if (listener == null) throw new IllegalArgumentException("listener is required");
        Channel channel = channels.get(run.getRunId());
        if (channel == null) throw new ReplayUnavailableException(run.getRunId());
        SubscriptionImpl subscription = new SubscriptionImpl(channel, listener);
        List<StreamEvent> replay;
        StreamEvent bufferMiss = null;
        boolean terminal;
        synchronized (channel) {
            if (channel.expired) throw new ReplayUnavailableException(run.getRunId());
            if (expectedStreamEpoch != null && expectedStreamEpoch.longValue() != channel.streamEpoch) {
                throw new ReplayResetException(run.getRunId(), channel.streamEpoch, channel.firstBufferedSeq,
                    run.getRevision());
            }
            if (afterSeq >= 0 && afterSeq + 1 < channel.firstBufferedSeq) {
                bufferMiss = local(run, StreamEvent.of("notice", Map.of(
                    "kind", "buffer_miss",
                    "firstAvailableSeq", channel.firstBufferedSeq,
                    "message", "events before firstAvailableSeq were trimmed; reload the thread for the full state")),
                    channel.streamEpoch);
            }
            replay = channel.buffer.stream()
                .filter(event -> event.getSeq() != null && event.getSeq() > afterSeq).toList();
            terminal = run.isTerminal();
            if (!terminal) channel.listeners.add(subscription);
        }
        subscription.finishReplay(bufferMiss, replay);
        if (terminal) subscription.complete();
        return subscription;
    }

    public int subscriberCount(RunHandle run) {
        Channel channel = run == null ? null : channels.get(run.getRunId());
        return channel == null ? 0 : channel.listeners.size();
    }

    public Map<String, Object> streamView(String runId) {
        Channel channel = runId == null ? null : channels.get(runId);
        if (channel == null) return Map.of();
        synchronized (channel) {
            return Map.of(
                "streamEpoch", channel.streamEpoch,
                "firstAvailableSeq", channel.firstBufferedSeq,
                "reservedThrough", channel.reservedThrough);
        }
    }

    void complete(RunHandle run) {
        Channel channel = channels.get(run.getRunId());
        if (channel == null) return;
        boolean drain;
        synchronized (channel) {
            channel.deliveries.addLast(new Delivery(null, List.copyOf(channel.listeners), true));
            drain = startDrain(channel);
        }
        if (drain) drain(channel);
    }

    void remove(String runId) {
        Channel channel = channels.remove(runId);
        if (channel != null) {
            synchronized (channel) {
                channel.expired = true;
                channel.buffer.clear();
                channel.deliveries.clear();
            }
            for (SubscriptionImpl subscription : List.copyOf(channel.listeners)) subscription.complete();
        }
    }

    private static boolean isPersistedEvent(String type) {
        return type != null && (PERSISTED_EVENT_TYPES.contains(type) || type.startsWith("goal_"));
    }

    private static StreamEvent local(RunHandle run, StreamEvent event, long streamEpoch) {
        event.setRunId(run.getRunId());
        event.setThreadId(run.getThreadId());
        event.setStreamEpoch(streamEpoch);
        event.setTs(System.currentTimeMillis());
        return event;
    }

    /** Drain one Run's delivery queue without holding the channel lock across potentially slow SSE sends. */
    private static void drain(Channel channel) {
        while (true) {
            Delivery delivery;
            synchronized (channel) {
                if (channel.expired) {
                    channel.deliveries.clear();
                    channel.draining = false;
                    return;
                }
                delivery = channel.deliveries.pollFirst();
                if (delivery == null) {
                    channel.draining = false;
                    return;
                }
            }
            if (delivery.complete) {
                delivery.listeners.forEach(SubscriptionImpl::complete);
            } else {
                delivery.listeners.forEach(listener -> listener.dispatch(delivery.event));
            }
        }
    }

    public interface Listener {
        void onEvent(StreamEvent event);

        void onComplete();
    }

    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    /** Raised when the run facts still exist but the bounded live replay channel has expired. */
    public static final class ReplayUnavailableException extends IllegalStateException {
        public ReplayUnavailableException(String runId) {
            super("event replay is no longer available for run " + runId);
        }
    }

    /** The client is attached to an older in-memory incarnation and must reload durable projections. */
    public static final class ReplayResetException extends IllegalStateException {
        private final long streamEpoch;
        private final long firstAvailableSeq;
        private final long runRevision;

        public ReplayResetException(String runId, long streamEpoch, long firstAvailableSeq, long runRevision) {
            super("event replay epoch changed for run " + runId);
            this.streamEpoch = streamEpoch;
            this.firstAvailableSeq = firstAvailableSeq;
            this.runRevision = runRevision;
        }

        public long streamEpoch() { return streamEpoch; }

        public long firstAvailableSeq() { return firstAvailableSeq; }

        public long runRevision() { return runRevision; }
    }

    private static final class Channel {
        private final RunPresentationState presentation = new RunPresentationState();
        private int mutationDepth;
        private final ArrayDeque<StreamEvent> buffer = new ArrayDeque<>();
        private final ArrayDeque<Delivery> deliveries = new ArrayDeque<>();
        private final long streamEpoch;
        private long firstBufferedSeq;
        private long nextSequence;
        private long reservedThrough;
        private boolean expired;
        private boolean draining;
        private final CopyOnWriteArrayList<SubscriptionImpl> listeners = new CopyOnWriteArrayList<>();

        private Channel(RunStreamCursorRecord cursor) {
            this.streamEpoch = cursor.streamEpoch();
            this.reservedThrough = cursor.reservedThrough();
            this.nextSequence = cursor.reservedThrough() + 1L;
            this.firstBufferedSeq = this.nextSequence;
        }

        private long nextSequence(RunStreamCursorStore cursors, String runId) {
            if (nextSequence > reservedThrough) {
                RunStreamCursorRecord lease = cursors.reserve(runId, streamEpoch, SEQUENCE_LEASE_SIZE);
                reservedThrough = lease.reservedThrough();
            }
            return nextSequence++;
        }
    }

    private record Delivery(StreamEvent event, List<SubscriptionImpl> listeners, boolean complete) {}

    private static final class SubscriptionImpl implements Subscription {
        private final Channel channel;
        private final Listener listener;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final List<StreamEvent> pending = new ArrayList<>();
        private boolean replaying = true;

        private SubscriptionImpl(Channel channel, Listener listener) {
            this.channel = channel;
            this.listener = listener;
        }

        synchronized void dispatch(StreamEvent event) {
            if (closed.get()) return;
            if (replaying) {
                pending.add(event);
            } else {
                deliver(event);
            }
        }

        synchronized void finishReplay(StreamEvent bufferMiss, List<StreamEvent> replay) {
            if (closed.get()) return;
            if (bufferMiss != null && !deliver(bufferMiss)) return;
            for (StreamEvent event : replay) {
                if (!deliver(event)) return;
            }
            replaying = false;
            List<StreamEvent> queued = List.copyOf(pending);
            pending.clear();
            for (StreamEvent event : queued) {
                if (!deliver(event)) return;
            }
        }

        synchronized void complete() {
            if (!closed.compareAndSet(false, true)) return;
            channel.listeners.remove(this);
            pending.clear();
            try {
                listener.onComplete();
            } catch (RuntimeException ignored) {
                // One faulty subscriber must not prevent the remaining subscribers from completing.
            }
        }

        @Override
        public synchronized void close() {
            if (!closed.compareAndSet(false, true)) return;
            channel.listeners.remove(this);
            pending.clear();
        }

        private boolean deliver(StreamEvent event) {
            try {
                listener.onEvent(event);
                return true;
            } catch (RuntimeException failure) {
                close();
                return false;
            }
        }
    }
}
