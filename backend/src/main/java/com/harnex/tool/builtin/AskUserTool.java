package com.harnex.tool.builtin;

import java.util.*;

import org.springframework.stereotype.Component;

import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.core.InteractionBroker;
import com.harnex.core.SubAgentBudget;
import com.harnex.model.StreamEvent;
import com.harnex.tool.*;
import com.harnex.tool.approval.UserPromptService;

/**
 * Ask the user a clarifying question (optionally with suggested options) and SUSPEND until they answer — mirroring the
 * approval / plan-review round-trip. Resolves to the user's free text or chosen option.
 *
 * <h2>Anti-nag is the whole design</h2> The model is strongly steered (system prompt + this description) to treat
 * asking as the EXCEPTION: it should decide small things itself (naming, formatting, defaults, equivalent approaches)
 * and only ask for genuine direction/scope changes, irreversible actions, or missing information it cannot infer. Three
 * hard guards back that up:
 * <ol>
 * <li><b>Per-turn cap</b> ({@code harnex.ask.max-per-turn}): once exceeded, further calls in the same turn
 * short-circuit to "proceed with best judgment" without prompting. The counter lives on the per-turn
 * {@link SubAgentBudget}.</li>
 * <li><b>Headless short-circuit</b>: with no SSE emitter (cron / MCP server / single-shot), the tool returns the
 * proceed sentinel immediately — autonomous runs never block.</li>
 * <li><b>Timeout</b> ({@code harnex.ask.timeout-ms}): an unanswered question returns the proceed sentinel so the loop
 * keeps moving.</li>
 * </ol>
 */
@Component
@ProjectRuntime
public class AskUserTool implements Tool {

    /** Returned when the model should stop asking and proceed on its own. */
    private static final String PROCEED_SENTINEL =
        "[no answer available — proceed with your best judgment and state the assumption you made]";

    private final UserPromptService prompts;
    private final AgentConfig cfg;

    public AskUserTool(UserPromptService prompts, AgentConfig cfg) {
        this.prompts = prompts;
        this.cfg = cfg;
    }

    @Override
    public String getName() {
        return "ask_user";
    }

    @Override
    public String getDescription() {
        return "Request indispensable information, or an optional clarification marked required=false. "
            + "Root tasks may ask the user; sub-agents return the question and options to their parent without a user wait. "
            + "First use available context and decide equivalent, low-impact details yourself. Bundle related questions "
            + "into one concise decision. Offer 2 or 3 distinct options, each with a short plain-text label and a "
            + "one-sentence description. Put a recommended option first when useful; never pad the list or add an "
            + "Other option, numbering or Markdown decorations. Omit options when specific free text is needed. "
            + "The user can always enter their own answer. Without a reply handler, optional questions may proceed; required questions stop "
            + "the current task and report the missing information.";
    }

    @Override
    public Set<ToolCapability> capabilities() {
        // Plan-mode safe (asking changes nothing in the world). Deliberately NOT concurrency-safe.
        return EnumSet.of(ToolCapability.PLAN_MODE_SAFE, ToolCapability.APPROVAL_FREE);
    }

    @Override
    public int timeoutMillis() {
        // The await blocks for the whole answer window; widen the executor watchdog to match (+buffer), clamped.
        long t = cfg.getAsk() == null ? 1_800_000L : cfg.getAsk().getTimeoutMs();
        return (int)Math.min(Integer.MAX_VALUE, Math.max(30_000L, t + 30_000L));
    }

    @Override
    public boolean awaitsUserInput() {
        return true;
    }

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("question", Map.of("type", "string", "minLength", 1, "maxLength", 1000,
            "description", "One concise, self-contained question. Do not repeat the options in its body."));
        props.put("options", Map.of("type", "array", "minItems", 2, "maxItems", 3,
            "items", Map.of("type", "object", "properties", Map.of(
                "label", Map.of("type", "string", "minLength", 1, "maxLength", 48, "description", "Short plain-text title; no numbering or recommendation suffix."),
                "description", Map.of("type", "string", "minLength", 1, "maxLength", 160, "description", "One sentence explaining this choice or its tradeoff."),
                "recommended", Map.of("type", "boolean", "description", "At most one option may be recommended.")),
                "required", List.of("label", "description"), "additionalProperties", false),
            "description", "Two or three meaningful choices. Omit this field for a free-text-only question; never add filler or Other."));
        props.put("required", Map.of("type", "boolean", "description",
            "True only when the answer is necessary to proceed; false for optional clarification. Never grants permissions."));
        return ToolDefinition.builder().name(getName()).description(getDescription())
            .parameters(Map.of("type", "object", "properties", props, "required", new String[] {"question"})).build();
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        String question = input.get("question") instanceof String text ? text.strip() : "";
        if (question.isEmpty() || question.codePointCount(0, question.length()) > 1000)
            return ToolResult.failure("question must contain 1 to 1000 characters");
        List<Choice> choices;
        try { choices = choices(input.get("options")); }
        catch (IllegalArgumentException invalid) { return ToolResult.failure(invalid.getMessage()); }
        List<String> options = choices.stream().map(Choice::answer).toList();

        boolean required = !Boolean.FALSE.equals(input.get("required"));
        if (ctx.getThread() != null && ctx.getThread().getSubagentDepth() > 0) {
            return required ? ctx.requireInput("Information needed from the parent agent: " + question
                + (options.isEmpty() ? "" : "\nSuggested options: " + String.join(" / ", options))
                + "\nThe parent should resolve this from its context, or consolidate a user question if necessary.")
                : ToolResult.ok(PROCEED_SENTINEL);
        }
        if (!InteractionBroker.available(ctx)) {
            return required ? ctx.requireInput("Required information: " + question) : ToolResult.ok(PROCEED_SENTINEL);
        }

        // Guard 1 — per-turn anti-nag cap. Counter lives on the per-turn budget so it resets each user turn.
        int maxPerTurn = cfg.getAsk() == null ? 2 : cfg.getAsk().getMaxPerTurn();
        SubAgentBudget budget = ctx.getBudget();
        if (budget != null && maxPerTurn > 0) {
            int n = budget.incrementAsks();
            if (n > maxPerTurn) {
                return required ? ctx.requireInput("Required information: " + question) : ToolResult.ok("[ask limit reached this turn (" + maxPerTurn
                    + ") — do not ask again now; proceed with your best judgment and state your assumption]");
            }
        }

        String tid = InteractionBroker.threadId(ctx);
        String callId = "ask-" + UUID.randomUUID();

        // Pre-register the waiter BEFORE emitting so a synchronous CLI consumer that answers from inside the emit
        // callback is not lost (mirrors ApprovalService / SubmitPlanTool).
        Map<String, Object> evt = new LinkedHashMap<>();
        evt.put("callId", callId);
        evt.put("threadId", tid == null ? "" : tid);
        evt.put("question", question);
        // String answers remain usable by terminal/IM clients; display metadata is aligned by option index.
        evt.put("options", options);
        evt.put("optionDetails", choices.stream().map(choice -> Map.of("label", choice.label(),
            "description", choice.description(), "recommended", choice.recommended())).toList());
        evt.put("allowFreeText", true);
        long timeout = cfg.getAsk() == null ? 1_800_000L : cfg.getAsk().getTimeoutMs();
        long deadline = System.currentTimeMillis() + InteractionBroker.timeout(ctx, timeout);
        evt.put("expiresAt", deadline);
        prompts.registerPending(tid, callId, evt);
        if (ctx.getEmit() != null) ctx.getEmit().accept(StreamEvent.of("user_question", evt));

        String answer = prompts.await(tid, callId, Math.max(1L, deadline - System.currentTimeMillis()),
            () -> InteractionBroker.stopped(ctx));
        // Guard 3 — timeout: keep the loop moving rather than hanging.
        if (answer == null) {
            if (required) return ctx.requireInput("Required information: " + question);
            return ToolResult.ok("[no response within the timeout — proceed with your best judgment and state the "
                + "assumption you made]");
        }
        return ToolResult.ok(answer);
    }

    private record Choice(String label, String description, boolean recommended) {
        String answer() { return description.isEmpty() ? label : label + " — " + description; }
    }

    private static List<Choice> choices(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> values)) throw new IllegalArgumentException("options must be an array");
        if (values.isEmpty()) return List.of();
        if (values.size() < 2 || values.size() > 3)
            throw new IllegalArgumentException("Provide 2 or 3 meaningful options, or omit options for a free-text question. Do not truncate alternatives or add filler.");
        List<Choice> choices = new ArrayList<>(); Set<String> labels = new HashSet<>(); int recommended = 0;
        for (Object value : values) {
            Choice choice;
            if (value instanceof String text) {
                // Already-running Agents may still use the preceding string schema.
                choice = new Choice(plain(text, 48, "option label"), "", false);
            } else if (value instanceof Map<?, ?> item) {
                if (item.get("recommended") != null && !(item.get("recommended") instanceof Boolean))
                    throw new IllegalArgumentException("recommended must be a boolean");
                choice = new Choice(plain(item.get("label"), 48, "option label"),
                    plain(item.get("description"), 160, "option description"), Boolean.TRUE.equals(item.get("recommended")));
            } else throw new IllegalArgumentException("Each option needs a short label and one-sentence description");
            if (!labels.add(choice.label().toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Option labels must be distinct");
            if (choice.recommended() && ++recommended > 1) throw new IllegalArgumentException("Recommend at most one option");
            choices.add(choice);
        }
        return List.copyOf(choices);
    }

    private static String plain(Object raw, int limit, String field) {
        if (!(raw instanceof String text)) throw new IllegalArgumentException(field + " must be text");
        text = text.replaceAll("[\\r\\n\\t]+", " ").strip()
            .replaceAll("^(?:[A-Za-z]|[0-9]{1,2})[.)、]\\s+", "")
            .replaceAll("\\*\\*(.+?)\\*\\*", "$1").replaceAll("`([^`]+)`", "$1");
        if (text.isBlank() || text.codePointCount(0, text.length()) > limit)
            throw new IllegalArgumentException(field + " must contain 1 to " + limit + " characters; shorten it without losing the choice's meaning");
        return text;
    }
}
