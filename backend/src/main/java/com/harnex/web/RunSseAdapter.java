package com.harnex.web;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.model.StreamEvent;
import com.harnex.run.RunEventBus;
import com.harnex.run.RunHandle;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/** HTTP/SSE adapter for the transport-neutral {@link RunEventBus}. */
@Slf4j
@Component
public class RunSseAdapter {

    private static final long HEARTBEAT_INTERVAL_MS = 15_000L;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentConfig config;
    private final RunEventBus events;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "run-sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public RunSseAdapter(AgentConfig config, RunEventBus events) {
        this.config = config;
        this.events = events;
    }

    public SseEmitter open(RunHandle run, long afterSeq) {
        return open(run, afterSeq, null);
    }

    public SseEmitter open(RunHandle run, long afterSeq, Long streamEpoch) {
        SseEmitter emitter = new SseEmitter(config.getSseTimeoutMs());
        Connection connection = new Connection(run, emitter);
        connection.start(Math.max(0L, afterSeq), streamEpoch);
        return emitter;
    }

    @PreDestroy
    public void close() {
        scheduler.shutdownNow();
    }

    private final class Connection implements RunEventBus.Listener {
        private final RunHandle run;
        private final SseEmitter emitter;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<RunEventBus.Subscription> subscription = new AtomicReference<>();
        private ScheduledFuture<?> heartbeat;

        private Connection(RunHandle run, SseEmitter emitter) {
            this.run = run;
            this.emitter = emitter;
        }

        void start(long afterSeq, Long streamEpoch) {
            heartbeat = scheduler.scheduleWithFixedDelay(this::keepAlive, HEARTBEAT_INTERVAL_MS,
                HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
            Runnable clientClosed = () -> closeConnection(false);
            emitter.onCompletion(clientClosed);
            emitter.onTimeout(clientClosed);
            emitter.onError(ignored -> clientClosed.run());
            RunEventBus.Subscription created;
            try {
                created = events.subscribe(run, afterSeq, streamEpoch, this);
            } catch (RuntimeException failure) {
                closed.set(true);
                heartbeat.cancel(false);
                throw failure;
            }
            subscription.set(created);
            if (closed.get()) {
                // Completion/send failure can happen synchronously while subscribe() is replaying, before the
                // subscription reference is published here. Remove that just-created subscription.
                created.close();
            }
        }

        private void keepAlive() {
            if (closed.get()) return;
            try {
                emitter.send(SseEmitter.event().comment("keep-alive"));
            } catch (Exception failure) {
                closeConnection(false);
            }
        }

        @Override
        public void onEvent(StreamEvent event) {
            if (closed.get()) return;
            try {
                emitter.send(SseEmitter.event().name(event.getType()).data(JSON.writeValueAsString(event)));
            } catch (Exception failure) {
                log.debug("run {} SSE send failed: {}", run.getRunId(), failure.getMessage());
                closeConnection(false);
            }
        }

        @Override
        public void onComplete() {
            closeConnection(true);
        }

        private void closeConnection(boolean serverInitiated) {
            if (!closed.compareAndSet(false, true)) return;
            if (heartbeat != null) heartbeat.cancel(false);
            RunEventBus.Subscription current = subscription.get();
            if (current != null) current.close();
            if (serverInitiated) {
                try {
                    emitter.complete();
                } catch (RuntimeException ignored) {
                    // The servlet container may already have closed the response.
                }
                return;
            }
        }
    }
}
