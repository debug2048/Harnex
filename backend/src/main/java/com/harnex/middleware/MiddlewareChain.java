package com.harnex.middleware;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.harnex.config.ProjectRuntime;
import com.harnex.core.AgentCtx;
import com.harnex.model.StreamEvent;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@ProjectRuntime
public class MiddlewareChain {

    private final List<Middleware> list;

    public MiddlewareChain(List<Middleware> list) {
        this.list = list;
    }

    @PostConstruct
    public void sort() {
        list.sort(Comparator.comparingInt(Middleware::order));
        log.info("MiddlewareChain: {}",
            list.stream().map(m -> m.getClass().getSimpleName() + "@" + m.order()).toList());
    }

    public void before(AgentCtx ctx) {
        for (Middleware m : list) {
            try {
                m.before(ctx);
            } catch (Exception e) {
                if (m.isCritical()) {
                    // Critical middleware (e.g. security guardrail / sandbox prep) must NOT be
                    // silently swallowed — propagate so AgentLoop can surface an `error` SSE frame
                    // and skip the LLM call entirely. Non-critical failures still log-and-continue.
                    log.error("Critical middleware {} before() failed — aborting agent run",
                        m.getClass().getSimpleName(), e);
                    throw new IllegalStateException("critical middleware " + m.getClass().getSimpleName() + " failed: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
                }
                log.error("Middleware {} before failed", m.getClass().getSimpleName(), e);
            }
        }
    }

    public void afterLlm(AgentCtx ctx) {
        for (Middleware m : list) {
            safe(m, "afterLlm", () -> m.afterLlm(ctx));
        }
    }

    public void afterTools(AgentCtx ctx) {
        for (Middleware m : list) {
            safe(m, "afterTools", () -> m.afterTools(ctx));
        }
    }

    public void after(AgentCtx ctx) {
        for (int i = list.size() - 1; i >= 0; i--) {
            Middleware m = list.get(i);
            safe(m, "after", () -> m.after(ctx));
        }
    }

    /** @return true if any middleware vetoed the call. */
    public boolean preToolCall(ToolCallEvent event) {
        for (Middleware m : list) {
            try {
                m.preToolCall(event);
            } catch (Exception e) {
                // FAIL CLOSED in the pre-tool-call gate: an exception from any middleware
                // (e.g. a SecurityVetoMiddleware that itself crashes) must NOT silently allow
                // the tool through. Convert the failure into an explicit veto with a clear reason.
                log.error("Middleware {} preToolCall threw \u2014 vetoing tool call as a safety measure",
                    m.getClass().getSimpleName(), e);
                event.setVetoed(true);
                event.setVetoReason(
                    "middleware " + m.getClass().getSimpleName() + " errored: " + e.getClass().getSimpleName());
                // Surface the middleware crash to the UI as a `warning` event so operators
                // (and users) can distinguish "the tool was deliberately vetoed by policy"
                // from "a middleware bug accidentally blocked a legitimate tool". Without
                // this signal the failure is only visible by tailing server logs.
                try {
                    AgentCtx ctx = event.getAgentCtx();
                    if (ctx != null) {
                        ctx.emit(StreamEvent.of("warning",
                            Map.of("kind", "middleware_error", "middleware", m.getClass().getSimpleName(), "tool",
                                event.getToolCall() == null ? "" : event.getToolCall().getName(), "exception",
                                e.getClass().getSimpleName(), "message", String.valueOf(e.getMessage()))));
                    }
                } catch (Throwable emitErr) {
                    // never let a UI hint mask the original veto
                    log.debug("middleware_error emit suppressed: {}", emitErr.getMessage());
                }
                return true;
            }
            if (event.isVetoed()) {
                return true;
            }
        }
        return false;
    }

    public void postToolCall(ToolCallEvent event) {
        for (Middleware m : list) {
            safe(m, "postToolCall", () -> m.postToolCall(event));
        }
    }

    public void onError(AgentCtx ctx, Exception error) {
        for (Middleware m : list) {
            safe(m, "onError", () -> m.onError(ctx, error));
        }
    }

    private static void safe(Middleware m, String phase, Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            // Full stack trace at ERROR — a swallowed middleware bug had previously been
            // invisible because we only logged getMessage() (often null) at WARN.
            log.error("Middleware {} {} failed", m.getClass().getSimpleName(), phase, e);
        }
    }
}
