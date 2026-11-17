package com.harnex.tool.builtin;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.harnex.config.ProjectRuntime;
import com.harnex.moa.MixtureOfAgentsService;
import com.harnex.moa.MixtureOfAgentsService.MoAResult;
import com.harnex.model.StreamEvent;
import com.harnex.tool.Tool;
import com.harnex.tool.ToolCapability;
import com.harnex.tool.ToolContext;
import com.harnex.tool.ToolDefinition;
import com.harnex.tool.ToolResult;

/**
 * Mixture-of-Agents wrapped as a Tool — fan out a single difficult sub-question to multiple reference models in
 * parallel, then synthesise via an aggregator model.
 *
 * <h2>Exposure policy (IMPORTANT)</h2> This tool is intentionally <strong>NOT</strong> registered into any toolset
 * preset ({@code read} / {@code inspect} / {@code act}) in {@code agent-config.yml} or
 * {@link com.harnex.toolset.ToolsetRegistry}'s fallback defaults. Reasons:
 * <ul>
 * <li>MoA spends N×provider tokens per call. If the LLM auto-invokes it inside a multi-turn agent loop, cost and
 * latency blow up super-linearly.</li>
 * <li>Each reference model re-runs the full (and growing) conversation history → reference answers degrade quickly into
 * "I suggest you call tool X" because they have no tool-call capability of their own.</li>
 * <li>Aggregator output cannot reconcile divergent {@code tool_use} payloads across models.</li>
 * </ul>
 * The tool is kept registered as a Spring component so operators can opt in by adding {@code mixture_of_agents} to a
 * preset (e.g. a dedicated {@code reasoning} preset) without a code change. Until then it is dormant:
 * {@link com.harnex.toolset.ToolsetRegistry#resolve} filters out tools not present in the active preset, so the model
 * never sees this tool's definition and cannot call it.
 *
 * <p>
 * The standalone REST entry point {@code POST /api/moa} remains the supported user-facing surface for one-shot MoA
 * calls.
 */
@Component
@ProjectRuntime
public class MoaTool implements Tool {

    private final MixtureOfAgentsService moa;

    public MoaTool(MixtureOfAgentsService moa) {
        this.moa = moa;
    }

    @Override
    public String getName() {
        return "mixture_of_agents";
    }

    @Override
    public String getDescription() {
        return "Run a single prompt through multiple reference models in parallel and synthesise "
            + "the best answer via an aggregator model. Use ONLY for an isolated, hard sub-question "
            + "(math proof, algorithm choice, design trade-off). Cost ≈ N × normal call.";
    }

    @Override
    public Set<ToolCapability> capabilities() {
        // MoA only fans out to LLM providers — outbound network reads, no local state mutation
        // and no filesystem touch. Not concurrency-safe (service-level single-permit batch gate)
        // and not plan-mode safe (spending money on an LLM round-trip during planning is hardly
        // the intent of "think before doing").
        return EnumSet.of(ToolCapability.READS_NETWORK);
    }

    /** Service has its own single-permit batch gate; concurrent calls would 503-bounce. */

    @Override
    public int timeoutMillis() {
        // References cap at 90s + aggregator at 120s + grace. Keep the outer watchdog above the
        // sum so a slow-but-legitimate batch is not cancelled before the service's own degraded
        // fallback kicks in.
        return 240_000;
    }

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("prompt", Map.of("type", "string", "description",
            "The single self-contained question to fan out across reference models."));
        return ToolDefinition.builder().name(getName()).description(getDescription())
            .parameters(Map.of("type", "object", "properties", props, "required", List.of("prompt")))
            .timeoutMs(timeoutMillis()).build();
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        Object p = input == null ? null : input.get("prompt");
        if (!(p instanceof String) || ((String)p).isBlank()) {
            return ToolResult.failure("missing required parameter: prompt");
        }
        try {
            MoAResult r = moa.invoke((String)p);
            if (r.getUsage() != null) {
                if (ctx != null && ctx.getBudget() != null && r.getUsage().total() > 0) {
                    ctx.getBudget().addTokens(r.getUsage().total());
                }
                if (ctx != null && ctx.getEmit() != null) {
                    Map<String, Object> usage = new LinkedHashMap<>(r.getUsage().eventData());
                    usage.put("kind", "moa");
                    usage.put("reportedComplete", r.isUsageComplete());
                    try {
                        ctx.getEmit().accept(StreamEvent.of("usage", usage));
                    } catch (RuntimeException ignored) {
                        // Usage is an observability side channel; a disconnected listener must not discard a costly
                        // aggregate result that has already completed successfully.
                    }
                }
            }
            String body = r.getAggregated() == null ? "" : r.getAggregated();
            if (r.isDegraded()) {
                return ToolResult.ok("[mixture_of_agents degraded: " + r.getDegradationReason() + "]\n\n" + body);
            }
            return ToolResult.ok(body);
        } catch (MixtureOfAgentsService.MoaBusyException busy) {
            return ToolResult.failure("mixture_of_agents busy; another batch is in flight — retry shortly");
        } catch (IllegalStateException cfgErr) {
            // Disabled / not configured: surface clearly so the model does not retry blindly.
            return ToolResult.failure("mixture_of_agents unavailable: " + cfgErr.getMessage());
        } catch (Exception e) {
            return ToolResult.failure(e);
        }
    }
}
