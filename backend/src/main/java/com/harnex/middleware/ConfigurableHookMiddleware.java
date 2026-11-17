package com.harnex.middleware;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.core.AgentCtx;
import com.harnex.model.StreamEvent;
import com.harnex.safety.SecretRedactor;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * Middleware that evaluates user-configured hook rules from {@code agent-config.yml → harnex.hooks.rules[]}.
 * <p>
 * Supports:
 * <ul>
 * <li><b>log</b> — log tool name + arguments / result (with secret-redaction)</li>
 * <li><b>veto</b> — block execution (preToolCall only) with a configurable reason</li>
 * <li><b>notify</b> — emit an SSE event for frontend notification</li>
 * </ul>
 * Tool matching uses glob-style patterns (* = any) on the tool name and an optional regex on the serialized arguments.
 * Patterns are compiled once per settings version to avoid per-call regex overhead.
 */
@Slf4j
@Component
@ProjectRuntime
public class ConfigurableHookMiddleware implements Middleware {

    /** Argument keys whose values we replace with `***` before logging. */
    private static final Pattern SECRET_KEY =
        Pattern.compile("(?i)(api[_-]?key|secret|password|passwd|token|authorization|auth|credential|private[_-]?key)");

    private final AgentConfig cfg;
    private record Compiled(List<AgentConfig.HookRule> rules, Map<AgentConfig.HookRule, Pattern> tools,
        Map<AgentConfig.HookRule, Pattern> arguments, Set<AgentConfig.HookRule> invalid) {}

    public ConfigurableHookMiddleware(AgentConfig cfg) { this.cfg = cfg; }

    @PostConstruct
    public void compile() { compiled(); }

    private Compiled compiled() { return cfg.derivedSettings(this, ConfigurableHookMiddleware::compileRules); }

    private static Compiled compileRules(AgentConfig cfg) {
        List<AgentConfig.HookRule> rules = cfg.getHooks().getRules();
        if (rules == null) rules = List.of();
        Map<AgentConfig.HookRule, Pattern> toolPatterns = new HashMap<>();
        Map<AgentConfig.HookRule, Pattern> argsPatterns = new HashMap<>();
        Set<AgentConfig.HookRule> invalid = new HashSet<>();
        for (AgentConfig.HookRule r : rules) {
            if (r.getToolPattern() != null && !"*".equals(r.getToolPattern())) {
                String regex = r.getToolPattern().replace(".", "\\.").replace("*", ".*");
                try {
                    toolPatterns.put(r, Pattern.compile(regex));
                } catch (Exception e) {
                    log.warn("[hook:{}] bad toolPattern '{}' — rule disabled", r.getName(), r.getToolPattern());
                    invalid.add(r);
                }
            }
            if (r.getArgsMatch() != null && !r.getArgsMatch().isBlank()) {
                try {
                    argsPatterns.put(r, Pattern.compile(r.getArgsMatch()));
                } catch (Exception e) {
                    log.warn("[hook:{}] bad argsMatch regex — rule disabled", r.getName());
                    invalid.add(r);
                }
            }
        }
        if (!rules.isEmpty()) {
            log.info("ConfigurableHookMiddleware compiled {} rule(s)", rules.size());
        }
        return new Compiled(List.copyOf(rules), Map.copyOf(toolPatterns), Map.copyOf(argsPatterns), Set.copyOf(invalid));
    }

    @Override
    public void preToolCall(ToolCallEvent event) {
        for (AgentConfig.HookRule rule : enabledRules("preToolCall")) {
            if (!matchesTool(rule, event)) {
                continue;
            }
            if (!matchesArgs(rule, event)) {
                continue;
            }

            switch (rule.getAction()) {
                case "log" -> log.info("[hook:{}] preToolCall tool={} args={}", rule.getName(),
                    event.getToolCall().getName(), redact(event.getParsedArguments()));
                case "veto" -> {
                    event.setVetoed(true);
                    event.setVetoReason(rule.getVetoReason());
                    log.warn("[hook:{}] VETOED tool={} reason={}", rule.getName(), event.getToolCall().getName(),
                        rule.getVetoReason());
                }
                case "notify" -> {
                    if (event.getAgentCtx() != null) {
                        event.getAgentCtx().emit(StreamEvent.of("hook_notify", Map.of("hook", rule.getName(), "phase",
                            "preToolCall", "tool", event.getToolCall().getName())));
                    }
                }
                default -> log.warn("[hook:{}] unknown action: {}", rule.getName(), rule.getAction());
            }
            if (event.isVetoed()) {
                break;
            }
        }
    }

    @Override
    public void postToolCall(ToolCallEvent event) {
        for (AgentConfig.HookRule rule : enabledRules("postToolCall")) {
            if (!matchesTool(rule, event)) {
                continue;
            }

            switch (rule.getAction()) {
                case "log" -> log.info("[hook:{}] postToolCall tool={} error={} duration={}ms", rule.getName(),
                    event.getToolCall().getName(), event.getResult() != null && event.getResult().isError(),
                    event.getResult() != null ? event.getResult().getDurationMs() : 0);
                case "notify" -> {
                    if (event.getAgentCtx() != null) {
                        event.getAgentCtx()
                            .emit(StreamEvent.of("hook_notify",
                                Map.of("hook", rule.getName(), "phase", "postToolCall", "tool",
                                    event.getToolCall().getName(), "error",
                                    event.getResult() != null && event.getResult().isError())));
                    }
                }
                default -> {
                    /* veto not applicable in postToolCall */ }
            }
        }
    }

    @Override
    public void onError(AgentCtx ctx, Exception error) {
        for (AgentConfig.HookRule rule : enabledRules("onError")) {
            switch (rule.getAction()) {
                case "log" -> log.info("[hook:{}] onError: {}", rule.getName(), error.getMessage());
                case "notify" -> ctx.emit(StreamEvent.of("hook_notify",
                    Map.of("hook", rule.getName(), "phase", "onError", "error", String.valueOf(error.getMessage()))));
                default -> {
                }
            }
        }
    }

    @Override
    public int order() {
        return MiddlewareOrder.CONFIGURABLE_HOOK;
    }

    // ── internals ──

    private List<AgentConfig.HookRule> enabledRules(String phase) {
        Compiled current = compiled();
        return current.rules().stream().filter(r -> !r.isDisabled() && !current.invalid().contains(r))
            .filter(r -> phase.equals(r.getPhase())).toList();
    }

    private boolean matchesTool(AgentConfig.HookRule rule, ToolCallEvent event) {
        Pattern p = compiled().tools().get(rule);
        if (p == null) {
            return true; // no pattern == wildcard
        }
        return p.matcher(event.getToolCall().getName()).matches();
    }

    private boolean matchesArgs(AgentConfig.HookRule rule, ToolCallEvent event) {
        Pattern p = compiled().arguments().get(rule);
        if (p == null) {
            return true;
        }
        return p.matcher(String.valueOf(event.getParsedArguments())).find();
    }

    /**
     * Replace values of keys that look like secrets with `***`, AND scrub any string value (regardless of key) through
     * {@link SecretRedactor} so embedded credentials inside free-form fields are caught too.
     *
     * <p>
     * The historical implementation only looked at key names — so a tool call shaped like {@code {"command": "curl -H
     * 'Authorization: Bearer sk-...' ..."}} or {@code {"command": "echo sk-abc123..."}} would have its secret dumped
     * verbatim into the application log under "{@code [hook:...] preToolCall args=...}". The bash tool, the shell-based
     * skills, and any future "execute this string" tool all carry their secrets inside a {@code command} /
     * {@code script} / {@code body} field that key-name matching cannot identify. Delegating string values to the same
     * {@link SecretRedactor} that scrubs tool RESULTS closes that gap with the same pattern set (sk-..., ghp_...,
     * AKIA..., AIza..., JWT, Bearer ..., password=..., api_key=...).
     *
     * <p>
     * Defensive copy throughout — the original args map is reused by the executor and must not be mutated by logging.
     */
    @SuppressWarnings("unchecked")
    private static Object redact(Object args) {
        if (args instanceof Map<?, ?> m) {
            Map<String, Object> out = new HashMap<>();
            for (var e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                Object v = e.getValue();
                if (SECRET_KEY.matcher(k).find()) {
                    out.put(k, "***");
                } else {
                    out.put(k, redact(v));
                }
            }
            return out;
        }
        if (args instanceof List<?> l) {
            // Tool calls in OpenAI / Anthropic schemas frequently nest sensitive payloads inside
            // arrays (e.g. {"tool_calls": [{"arguments": {"api_key": "sk-..."}}]}). Without this
            // branch the redactor would log the secret verbatim.
            List<Object> out = new ArrayList<>(l.size());
            for (Object v : l) {
                out.add(redact(v));
            }
            return out;
        }
        if (args instanceof CharSequence cs) {
            // PII intentionally NOT redacted at the hook log layer: legitimate user-facing
            // tool args (e.g. {"to":"alice@example.com"}) commonly contain emails / phone
            // numbers and the operator running the hook log usually wants to see them for
            // troubleshooting. Pure credential scrubbing only — matches the SECRET_KEY
            // posture of "always-on, no opt-in".
            return SecretRedactor.redact(cs.toString(), false);
        }
        return args;
    }
}
