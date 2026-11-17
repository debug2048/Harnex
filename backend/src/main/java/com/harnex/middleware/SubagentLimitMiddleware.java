package com.harnex.middleware;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import com.harnex.config.AgentConfig;
import com.harnex.observability.HarnexMetrics;
import com.harnex.config.ProjectRuntime;
import com.harnex.core.AgentCtx;
import com.harnex.model.ChatMessage;
import com.harnex.model.StreamEvent;

/** Clamp sub-agent invocations per turn (Hermes/DeerFlow idea). */
@Component
@ProjectRuntime
public class SubagentLimitMiddleware implements Middleware {

    private final AgentConfig cfg;
    private final HarnexMetrics metrics;

    public SubagentLimitMiddleware(AgentConfig cfg) {
        this(cfg, null);
    }

    @Autowired
    public SubagentLimitMiddleware(AgentConfig cfg, HarnexMetrics metrics) {
        this.cfg = cfg;
        this.metrics = metrics;
    }

    @Override
    public int order() {
        return MiddlewareOrder.SUBAGENT_LIMIT;
    }

    @Override
    public void afterLlm(AgentCtx ctx) {
        var last = ctx.getLastToolCalls();
        if (last == null || last.isEmpty()) {
            return;
        }
        // Pass 1: collect IDs of delegate calls beyond the cap (do NOT mutate `last` yet, so
        // we can build the keepIds set for pass 2 atomically).
        Set<String> dropIds = new HashSet<>();
        int limit = Math.max(1, cfg.getSubagent().getMaxPerThread());
        int delegates = 0;
        for (var c : last) {
            if ("delegate".equals(c.getName())) {
                if (++delegates > limit) {
                    dropIds.add(c.getId());
                }
            }
        }
        if (dropIds.isEmpty()) {
            return;
        }
        int requested = delegates;
        int dropped = dropIds.size();
        if (metrics != null) metrics.delegateLimitExceeded.increment(dropped);
        // Pass 2: prune both the live tool-call list and the assistant message in lockstep so
        // we never end up with an orphaned tool_call ID that the provider would reject on the
        // next turn.
        last.removeIf(c -> dropIds.contains(c.getId()));
        var msgs = ctx.getThread().getMessages();
        if (!msgs.isEmpty()) {
            ChatMessage lastMsg = msgs.get(msgs.size() - 1);
            if ("assistant".equals(lastMsg.getRole()) && lastMsg.getToolCalls() != null) {
                lastMsg.getToolCalls().removeIf(tc -> dropIds.contains(tc.getId()));
            }
        }
        String reminder = "Delegate limit: requested " + requested + ", admitted " + limit + ". After consuming "
            + "these results, submit the remaining " + dropped + " in the next wave.";
        ctx.queueRequestReminder("delegate-limit:" + ctx.getIteration(), reminder);
        ctx.emit(StreamEvent.of("notice", Map.of(
            "kind", "delegate_limit_exceeded", "requested", requested, "admitted", limit, "dropped", dropped)));
    }
}
