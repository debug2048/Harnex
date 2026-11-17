package com.harnex.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;

import com.harnex.goal.GoalPromptContext;
import com.harnex.middleware.TitleMiddleware;
import com.harnex.model.ChatMessage;
import com.harnex.model.ConversationThread;
import com.harnex.model.HumanTurnContext;
import com.harnex.conversation.ContextEvidence;
import com.harnex.conversation.ContextTokens;
import com.harnex.conversation.RequestContextOverlay;

/** Builds bounded, request-only continuity context from durable human turns. */
public final class ConversationContinuity {

    private static final int MAX_CONTEXT_TURNS = 2;
    private static final int MAX_ANCHOR_CHARS = 1_600;
    private static final int MAX_CONTEXT_CHARS = 600;
    private static final int MAX_CURRENT_CHARS = 2_400;
    private static final String CLOSE_TAG = "</conversation_continuity>";

    private ConversationContinuity() {}

    /** Exact latest human request from the current branch, independent of model-history compression. */
    public static String latestHumanRequest(ConversationThread thread) {
        return context(thread).latest();
    }

    public static String originalHumanRequest(ConversationThread thread) {
        return context(thread).original();
    }

    /** Keep an explicit request and up to two follow-ups as a hint for pronouns and short continuation requests. */
    public static TopicContext currentTopic(ConversationThread thread) {
        HumanTurnContext context = context(thread);
        return new TopicContext(context.anchor(), context.contextTurns(), context.latest());
    }

    public record TopicContext(String anchor, List<String> contextTurns, String latest) {}

    public static int humanTurnCount(ConversationThread thread) { return context(thread).turnCount(); }

    /** Replace the projection, including on branch switches and retries; equal input texts are separate turns. */
    public static void restore(ConversationThread thread, List<ChatMessage> history) {
        HumanTurnContext context = emptyContext();
        for (String request : humanRequests(history)) context = append(context, request);
        thread.setHumanTurnContext(context);
    }

    /** Call once for an applied human input, before compression can rewrite its model message. */
    public static void appendHumanRequest(ConversationThread thread, ChatMessage message) {
        HumanTurnContext context = context(thread);
        for (String request : humanRequests(List.of(message))) context = append(context, request);
        thread.setHumanTurnContext(context);
    }

    private static HumanTurnContext context(ConversationThread thread) {
        if (thread == null) return emptyContext();
        if (thread.getHumanTurnContext() != null) return thread.getHumanTurnContext();
        HumanTurnContext derived = emptyContext();
        for (String request : humanRequests(thread.getMessages())) derived = append(derived, request);
        return derived;
    }

    private static HumanTurnContext emptyContext() { return new HumanTurnContext(null, null, List.of(), null, 0); }

    private static HumanTurnContext append(HumanTurnContext previous, String request) {
        boolean dependent = previous.latest() != null && !isExplicitTopicSwitch(request)
            && (isContextDependent(request) || isConstraintOnly(request));
        List<String> intervening = new ArrayList<>();
        if (dependent) {
            intervening.addAll(previous.contextTurns());
            if (!Objects.equals(previous.latest(), previous.anchor())) intervening.add(previous.latest());
            while (intervening.size() > MAX_CONTEXT_TURNS) intervening.remove(0);
        }
        return new HumanTurnContext(previous.original() == null ? request : previous.original(),
            dependent ? previous.anchor() : request, intervening, request,
            previous.turnCount() == Integer.MAX_VALUE ? Integer.MAX_VALUE : previous.turnCount() + 1);
    }

    /** Admit a preview for legacy callers. The main loop commits its final admitted request explicitly. */
    static List<ChatMessage> requestMessages(AgentCtx ctx, int iteration) {
        List<ChatMessage> request = previewRequestMessages(ctx, iteration);
        commitPreparedRequest(ctx, request);
        ctx.drainRequestReminders();
        return request;
    }

    static List<ChatMessage> previewRequestMessages(AgentCtx ctx, int iteration) {
        return requestMessages(ctx, iteration, List.copyOf(ctx.getRequestReminders()));
    }

    private static List<ChatMessage> requestMessages(AgentCtx ctx, int iteration, List<String> reminders) {
        ConversationThread thread = ctx.getThread();
        String executionRules = ctx.getOptions() == null ? "" : thread.getSubagentDepth() > 0
            ? ctx.getOptions().subtaskInstructions() : ctx.getOptions().instructions();
        List<ChatMessage> originals = thread.getMessages();
        ContextEvidence.Collector visible = visibleEvidence(originals, false);
        var factProjection = ctx.getRegisteredFactProjection() == null ? null : ctx.getRegisteredFactProjection().apply(visible);
        String registeredFacts = factProjection == null ? legacyFacts(thread) : factProjection.text();
        if (factProjection != null && registeredFacts.isBlank() && factProjection.partial()) registeredFacts =
            "<registered_conversation_facts>Registered facts exceed this projection budget; use session_fact(action=list). "
                + "Coverage is incomplete; earlier constraints were not revoked.</registered_conversation_facts>";
        Map<String, String> previous = latestState(originals);
        Map<String, String> blocks = new LinkedHashMap<>();
        blocks.put("continuity", shouldRenderContinuity(thread, iteration) ? render(thread) : "");
        blocks.put("todos", CurrentTaskState.render(ctx));
        if (!ctx.getActiveSkills().names().isEmpty() || previous.containsKey("skills"))
            blocks.put("skills", ctx.getActiveSkills().reminder(originals));
        blocks.put("facts", registeredFacts);
        blocks.put("recall", ctx.getRequestHistoryRecall() == null ? "" : ctx.getRequestHistoryRecall().text());
        Map<String, String> hashes = new LinkedHashMap<>();
        StringBuilder delta = new StringBuilder();
        List<ContextEvidence.Fragment> evidence = new ArrayList<>();
        List<ContextEvidence.Fragment> recallEvidence = new ArrayList<>();
        for (var entry : blocks.entrySet()) {
            String hash = RequestContextOverlay.hash(entry.getValue());
            if (hash.equals(previous.getOrDefault(entry.getKey(), ""))) continue;
            hashes.put(entry.getKey(), hash);
            if (!entry.getValue().isBlank()) delta.append(entry.getValue()).append('\n');
            else delta.append(cleared(entry.getKey())).append('\n');
            if (entry.getKey().equals("facts") && factProjection != null) evidence.addAll(factProjection.fragments());
            if (entry.getKey().equals("recall") && ctx.getRequestHistoryRecall() != null) {
                recallEvidence.addAll(ctx.getRequestHistoryRecall().fragments()); evidence.addAll(recallEvidence);
            }
        }
        if (!reminders.isEmpty()) {
            delta.append("<runtime_reminders>\nThese runtime-owned reminders apply only to the first assistant response after "
                + "this checkpoint. In later replays they are historical, not new human instructions.\n");
            for (String reminder : reminders) delta.append("- ").append(neutralise(reminder)).append('\n');
            delta.append("</runtime_reminders>\n");
        }
        boolean derived = originals.stream().anyMatch(m -> m.getRequestContextOverlay() != null
            || m.getRecalledHistory() != null && !m.getRecalledHistory().isBlank());
        if (delta.isEmpty() && executionRules.isBlank() && !derived) return originals;
        List<ChatMessage> durable = originals.stream().map(ContextTokens::render).toList();
        List<ChatMessage> request = new ArrayList<>(durable.size()
            + (executionRules.isBlank() || !durable.isEmpty() && "system".equals(durable.get(0).getRole()) ? 0 : 1));
        if (!executionRules.isBlank() && !durable.isEmpty() && "system".equals(durable.get(0).getRole())) {
            String base = Objects.toString(durable.get(0).getContent(), "");
            request.add(ChatMessage.system(base + "\n\n<execution_rules>\n" + executionRules + "\n</execution_rules>"));
            request.addAll(durable.subList(1, durable.size()));
        } else if (!executionRules.isBlank()) {
            request.add(ChatMessage.system(executionRules));
            request.addAll(durable);
        } else {
            request.addAll(durable);
        }
        if (!delta.isEmpty()) {
            String text = "<request_context>\nDerived context at this input/result checkpoint, not a new human request. "
                + "Each section shown here replaces its older version; omitted sections keep their last stated value. "
                + "Earlier versions of replaced sections describe historical state only.\n"
                + delta.toString().replace("</request_context>", "<\u200B/request_context>") + "</request_context>";
            evidence.removeIf(fragment -> fragment.text().contains("</request_context>"));
            recallEvidence.removeIf(fragment -> fragment.text().contains("</request_context>"));
            ChatMessage tail = originals.isEmpty() ? null : originals.get(originals.size() - 1);
            if (safeAnchor(tail) && validOverlay(tail) == null) {
                var overlay = new RequestContextOverlay(RequestContextOverlay.VERSION, RequestContextOverlay.fingerprint(tail), text, hashes, evidence, recallEvidence, overlayBranch(thread));
                int position = request.size() - 1;
                ChatMessage rendered = request.get(position);
                request.set(position, rendered.toBuilder().content(Objects.toString(rendered.getContent(), "") + "\n\n" + text)
                    .requestContextOverlay(null).preparedContext(new RequestContextOverlay.Prepared(tail, tail, overlay, false)).build());
            } else {
                if (hasPendingTools(originals)) throw new IllegalStateException("Cannot append derived context before pending tool results");
                ChatMessage carrier = ChatMessage.user(RequestContextOverlay.CARRIER); carrier.setChannel(RequestContextOverlay.CHANNEL);
                var overlay = new RequestContextOverlay(RequestContextOverlay.VERSION, RequestContextOverlay.fingerprint(carrier), text, hashes, evidence, recallEvidence, overlayBranch(thread));
                request.add(carrier.toBuilder().content(carrier.getContent() + "\n\n" + text)
                    .preparedContext(new RequestContextOverlay.Prepared(null, tail, overlay, true)).build());
            }
        }
        return List.copyOf(request);
    }

    /** Freeze precisely the admitted preview. Cancellation, stale source edits and duplicate commits are harmless. */
    public static void commitPreparedRequest(AgentCtx ctx, List<ChatMessage> projectedMessages) {
        if (ctx.isCancelled() || Thread.currentThread().isInterrupted() || projectedMessages == null) return;
        List<ChatMessage> originals = ctx.getThread().getMessages();
        for (ChatMessage projected : projectedMessages) {
            var prepared = projected.getPreparedContext();
            if (prepared == null || prepared.overlay() == null) continue;
            if (prepared.appended()) {
                if (originals.stream().anyMatch(m -> Objects.equals(m.getRequestContextOverlay(), prepared.overlay()))) continue;
                ChatMessage tail = originals.isEmpty() ? null : originals.get(originals.size() - 1);
                if (tail != prepared.expectedTail() || hasPendingTools(originals)
                    || tail != null && !Objects.equals(prepared.expectedTailFingerprint(), RequestContextOverlay.fingerprint(tail))) continue;
                ChatMessage carrier = ChatMessage.user(RequestContextOverlay.CARRIER); carrier.setChannel(RequestContextOverlay.CHANNEL);
                if (!prepared.overlay().validFor(carrier)) continue;
                carrier.setRequestContextOverlay(prepared.overlay()); originals.add(carrier);
            } else if (prepared.origin() != null && originals.stream().anyMatch(m -> m == prepared.origin())
                && prepared.overlay().validFor(prepared.origin())) {
                var existing = validOverlay(prepared.origin());
                if (existing == null || existing.equals(prepared.overlay())) prepared.origin().setRequestContextOverlay(prepared.overlay());
            }
        }
    }

    /** Commit-time compaction hook: discard replay-only snapshots, then rebuild current bounded state in preview. */
    public static boolean discardDerivedOverlays(AgentCtx ctx) {
        if (ctx.isCancelled()) return false;
        List<ChatMessage> kept = new ArrayList<>(); boolean changed = false;
        for (ChatMessage message : ctx.getThread().getMessages()) {
            if (message.getRequestContextOverlay() == null) { kept.add(message); continue; }
            changed = true;
            if (RequestContextOverlay.isCarrier(message)) continue;
            kept.add(message.toBuilder().requestContextOverlay(null).preparedContext(null).build());
        }
        if (changed) ctx.getThread().setMessages(kept);
        return changed;
    }

    public static ContextEvidence.Collector visibleEvidence(List<ChatMessage> messages) {
        return visibleEvidence(messages, true);
    }
    private static ContextEvidence.Collector visibleEvidence(List<ChatMessage> messages, boolean includeFactOverlays) {
        var evidence = new ContextEvidence.Collector();
        for (ChatMessage message : messages) {
            if (message.getContextEvidence() != null) for (var fragment : message.getContextEvidence()) {
                String body = Objects.toString(message.getContent(), "") + "\n" + Objects.toString(message.getRecalledHistory(), "");
                if (fragment != null && fragment.text() != null && body.contains(fragment.text())) evidence.add(fragment);
            }
            var overlay = validOverlay(message);
            if (overlay == null && message.getPreparedContext() != null) overlay = message.getPreparedContext().overlay();
            if (overlay != null) evidence.addAll(includeFactOverlays ? overlay.evidence() : overlay.recallEvidence());
        }
        return evidence;
    }

    private static RequestContextOverlay validOverlay(ChatMessage message) {
        var overlay = message == null ? null : message.getRequestContextOverlay();
        return overlay != null && overlay.validFor(message) ? overlay : null;
    }
    private static Map<String, String> latestState(List<ChatMessage> messages) {
        Map<String, String> state = new LinkedHashMap<>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            var overlay = validOverlay(messages.get(i));
            if (overlay != null) overlay.stateHashes().forEach(state::putIfAbsent);
        }
        return state;
    }
    private static boolean safeAnchor(ChatMessage message) {
        return message != null && ("user".equals(message.getRole()) || "tool".equals(message.getRole()))
            && (message.getResponseOutputItems() == null || message.getResponseOutputItems().isBlank())
            && (message.getToolCalls() == null || message.getToolCalls().isEmpty());
    }
    private static boolean hasPendingTools(List<ChatMessage> messages) {
        Set<String> pending = new HashSet<>();
        for (ChatMessage message : messages) {
            if (message.getToolCalls() != null) for (var call : message.getToolCalls()) if (call != null && call.getId() != null) pending.add(call.getId());
            if ("tool".equals(message.getRole())) pending.remove(message.getToolCallId());
        }
        return !pending.isEmpty();
    }
    private static String legacyFacts(ConversationThread thread) {
        Object value = thread.getState() == null || thread.getState().getExtras() == null ? null
            : thread.getState().getExtras().get("registeredConversationFacts");
        return value instanceof String text ? text : "";
    }
    private static RequestContextOverlay.Branch overlayBranch(ConversationThread thread) {
        var extras = thread.getState() == null ? null : thread.getState().getExtras();
        if (extras == null || !(extras.get("currentHistoryScope") instanceof String scope)
            || !(extras.get("latestHistoryUserId") instanceof String source)
            || !(extras.get("currentHistoryRunId") instanceof String run)) return null;
        return new RequestContextOverlay.Branch(scope, source, run);
    }
    private static String cleared(String kind) {
        return switch (kind) {
            case "skills" -> "<active_skill_context>No missing skill-body reminder is active for this execution. Earlier reminders are historical.</active_skill_context>";
            case "todos" -> "<current_task_state>Current Todo state is unavailable; earlier snapshots are historical. "
                + "Unavailable is not an empty or completed plan.</current_task_state>";
            case "facts" -> "<registered_conversation_facts>No additional registered quotations are selected at this checkpoint; "
                + "they may already be covered by visible source text. This replaces the earlier injected fact projection, "
                + "not the underlying sources. Do not treat an older snapshot as the current registration set or infer revocation from omission.</registered_conversation_facts>";
            case "continuity" -> "<conversation_continuity>The current human request and required topic context are visible "
                + "in the conversation. Prior continuity snapshots are historical; follow the latest human input.</conversation_continuity>";
            default -> "<history_context>No new recall is selected at this checkpoint. Earlier excerpts remain historical "
                + "references, not current task state.</history_context>";
        };
    }

    private static boolean shouldRenderContinuity(ConversationThread thread, int iteration) {
        if (thread == null || thread.getSubagentDepth() > 0) return false;
        int humanTurns = humanTurnCount(thread);
        long compressionGeneration = thread.getState() == null || thread.getState().getCompression() == null ? 0
            : thread.getState().getCompression().getContextGeneration();
        // A first-ever, first-iteration request is already the most recent user message. Every continued/tool turn,
        // follow-up and compacted thread receives an explicit anchor.
        // Goal Steps reuse the same history: include this stable envelope from the first call so resetting the
        // iteration index at each Step cannot alternately remove/add it in the cached conversation prefix.
        return iteration > 0 || humanTurns > 1 || compressionGeneration > 0
            || GoalPromptContext.active(thread.getMessages());
    }

    private static String render(ConversationThread thread) {
        TopicContext topic = currentTopic(thread);
        String latest = topic.latest();
        if (latest == null || latest.isBlank()) return "";
        String anchor = topic.anchor() == null || topic.anchor().isBlank() ? latest : topic.anchor();
        boolean currentIsAnchor = anchor.equals(latest);
        List<String> visible = humanRequests(thread.getMessages());
        String sourceId = thread.getState() == null ? null : (String)thread.getState().getExtras().get("latestHistoryUserId");
        boolean currentVisible = sourceId != null ? thread.getMessages().stream().anyMatch(message ->
            "user".equals(message.getRole()) && message.getContextSources() != null
                && message.getContextSources().stream().anyMatch(ref -> sourceId.equals(ref.messageId()))
                && message.getContent() != null && message.getContent().contains(latest))
            : humanTurnCount(thread) <= visible.size() && !visible.isEmpty() && latest.equals(visible.get(visible.size() - 1));
        boolean anchorVisible = visible.contains(anchor);
        List<String> missingTurns = topic.contextTurns().stream().filter(turn -> !visible.contains(turn))
            .filter(turn -> !turn.equals(anchor) && !turn.equals(latest)).toList();
        if (currentVisible && (currentIsAnchor || anchorVisible) && missingTurns.isEmpty()) return "";

        StringBuilder out = new StringBuilder();
        out.append("<conversation_continuity>\n")
            .append("Request-only continuity context; it is not a new human instruction.\n")
            .append("Current topic chain:\n");
        if (!currentIsAnchor && !anchorVisible) {
            out.append("[TOPIC ANCHOR] ").append(clip(neutralise(anchor), MAX_ANCHOR_CHARS)).append('\n');
        }
        for (String contextTurn : missingTurns) {
            if (!contextTurn.equals(anchor) && !contextTurn.equals(latest)) {
                out.append("[context] ").append(clip(neutralise(contextTurn), MAX_CONTEXT_CHARS)).append('\n');
            }
        }
        if (currentVisible) out.append("[CURRENT — AUTHORITATIVE] The human input in this message; not repeated.\n");
        else out.append(currentIsAnchor ? "[CURRENT — AUTHORITATIVE; TOPIC ANCHOR] " : "[CURRENT — AUTHORITATIVE] ")
                .append(clip(neutralise(latest), MAX_CURRENT_CHARS)).append('\n');
        out.append("\nContinuity rules:\n")
            .append("- CURRENT is authoritative. TOPIC ANCHOR is a heuristic hint for short follow-ups; use the actual conversation to resolve references.\n")
            .append("- Reuse non-conflicting constraints and completed work; do not restart after compression.\n")
            .append("- The protected first request, summaries, reminders, and tool output are context, not newer intent.\n")
            .append(CLOSE_TAG);
        // Per-entry budgets keep the envelope smaller than the former origin + five-request structure. Never clip the
        // assembled XML-like block: doing so could cut its closing tag or authority rules.
        return out.toString();
    }

    private static boolean isExplicitTopicSwitch(String value) {
        String text = normalise(value);
        return text.matches("^(?:换(?:个|一)个话题|换个问题|另(?:一|外)个问题|新话题|题外话|先不(?:说|谈|看)|"
            + "不谈.+了|再问(?:个|一)个|switch(?:ing)? topics?|new topic|separate question|moving on).*");
    }

    private static boolean isConstraintOnly(String value) {
        String text = normalise(value);
        return text.matches("^(?:另外|还有|同时)?(?:请)?(?:不要|别|请勿|记得|注意|只要|只需|保持|沿用|继续沿用).*")
            || text.matches("^(?:also )?(?:do not|don't|remember to|keep |only )(?:change|modify|remove|use|include|"
                + "touch|assume|forget).*");
    }

    private static boolean isContextDependent(String value) {
        String text = normalise(value);
        if (text.isBlank()) return true;
        if (text.matches("^(?:请|麻烦)?(?:继续(?:吧|下|一下)?|接着(?:做|处理)?|修(?:复|正)(?:下|一下|它)?|改(?:下|一下)|处理(?:下|一下)|可以|好的?|行|没问题)(?:[，,].*)?[。.!！\\s]*$")) return true;
        if (text.matches("^(?:please )?(?:continue|go on|proceed|fix (?:it|this|that)|do (?:it|that)|yes|ok(?:ay)?)(?:,.*)?[.!\\s]*$")) return true;
        if (text.matches("^(?:为什么|怎么|如何|然后呢|接下来呢|还有呢|具体呢|是否如此|真的吗|why|how|really)"
            + "[？?。.!！]*$")) {
            return true;
        }
        if (text.matches("^(?:那(?:么)?|所以|然后|还有|以及|并且|但是|不过|可(?:是)?|这种情况|这样做|这么做|"
            + "它|它们|其|其中|上述|前面|刚才|具体|同样|也就是说|换句话说).*")
            || text.matches(".*(?:它|它们|上述|前面|刚才|这种情况|这样做|这么做|为什么这样|为什么这么).*")
            || text.matches("^(?:then|so|and|but|what about|how about|does it|is it|can it|could it|would it|"
                + "it)\\b.*")) {
            return true;
        }
        return false;
    }

    private static String normalise(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }

    private static List<String> humanRequests(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (ChatMessage message : messages) {
            if (message == null || !"user".equals(message.getRole())) continue;
            String content = message.getOriginalInput() != null && !message.getOriginalInput().isBlank()
                ? message.getOriginalInput() : message.getContent();
            if (content == null || content.isBlank() || TitleMiddleware.isInternalUserMessage(content.stripLeading())) {
                continue;
            }
            out.add(content.strip());
        }
        return out;
    }

    private static String neutralise(String value) {
        if (value == null) return "";
        return value.replace(CLOSE_TAG, "<\u200B/conversation_continuity>")
            .replace("</runtime_reminders>", "<\u200B/runtime_reminders>");
    }

    /** Balanced clipping keeps both the subject at the start and constraints commonly placed at the end. */
    private static String clip(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) return value == null ? "" : value;
        String marker = "\n…[continuity clipped]…\n";
        int available = Math.max(0, maxChars - marker.length());
        int head = available * 2 / 3;
        return value.substring(0, head) + marker + value.substring(value.length() - (available - head));
    }
}
