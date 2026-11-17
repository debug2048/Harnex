package com.harnex.core;

import java.util.*;

import com.harnex.model.ChatMessage;
import com.harnex.model.ConversationThread;
import com.harnex.model.ToolCall;

import lombok.extern.slf4j.Slf4j;

/**
 * History-integrity sweep.
 *
 * <p>
 * OpenAI and Anthropic both reject a request when {@code assistant.tool_calls[*].id} are not each followed by a
 * matching {@code role=tool} (or {@code tool_result}) message — the provider returns 400 with
 * {@code "messages with role 'tool' must be a response to a preceding message with 'tool_calls'"} (OpenAI) or
 * {@code "tool_use ids do not match"} (Anthropic). Harnex's runtime path is careful to never produce such histories,
 * but stored {@code thread.json} can still acquire orphans through:
 *
 * <ul>
 * <li>Pre-fix bugs (e.g. middleware mutating {@code lastToolCalls} without updating the persisted assistant message —
 * fixed in 1.0 but stored threads from 0.x still carry the damage).</li>
 * <li>Process crash / power loss between {@code assistant} message persist and the tool-result append.</li>
 * <li>Manual {@code thread.json} edits or out-of-band imports.</li>
 * <li>Snapshot rollback that lands in the middle of an iteration.</li>
 * </ul>
 *
 * <p>
 * Sweep semantics ({@link #sweep(ConversationThread)}):
 *
 * <ol>
 * <li>For each {@code assistant.tool_calls[].id} that has no later {@code tool} message with matching
 * {@code toolCallId}, synthesise a placeholder tool result message inserted immediately after the assistant message
 * that owns the call. Placeholder content makes the recovery visible to the model so it can course-correct.</li>
 * <li>Drop any {@code role=tool} message whose {@code toolCallId} does not match a {@link ToolCall#getId} from a
 * preceding {@code assistant} message — these are orphans the provider would reject.</li>
 * <li>Drop {@code role=tool} messages with null/blank {@code toolCallId} (cannot be referenced).</li>
 * </ol>
 *
 * <p>
 * Idempotent: running twice on the same history is a no-op (synthesised placeholders are themselves valid tool messages
 * with a {@code toolCallId} that matches an assistant call, so the second pass finds no orphans).
 *
 * <p>
 * Cheap: O(N) over the message list, single pass to index + single pass to repair. Safe to call in the hot path of
 * every root run dispatch.
 */
@Slf4j
public final class HistoryIntegrity {

    private HistoryIntegrity() {}

    /** Placeholder content for a recovered missing tool result. Tagged so audit / debug can grep it. */
    static final String MISSING_PLACEHOLDER =
        "[history-sweep: original tool result missing (process crash, snapshot rollback, or manual edit). "
            + "Treat this as an empty / failed call and continue.]";

    /**
     * Outcome of a sweep, returned so callers can log / emit metrics / surface a notice to the user. All counters
     * default to 0 when the history was already clean.
     */
    public record SweepResult(int missingResultsInserted, int orphanResultsDropped) {
        public int total() {
            return missingResultsInserted + orphanResultsDropped;
        }

        public boolean isClean() {
            return total() == 0;
        }
    }

    public static SweepResult sweep(ConversationThread thread) {
        if (thread == null || thread.getMessages() == null || thread.getMessages().isEmpty()) {
            return new SweepResult(0, 0);
        }
        List<ChatMessage> msgs = thread.getMessages();

        // Pass 1: build callId -> assistantIndex map. Insertion order is preserved so we can place
        // synthesised results in the right slot. A call id can in theory appear in multiple
        // assistant messages (model re-emitting the same id), in which case the LATEST owner wins
        // \u2014 that's the one whose tool result the provider expects to see next.
        int orphanDropped = 0;

        // Pass 2: decide which tool messages are valid, working against ORIGINAL indices so a
        // removal can never skew a later comparison. A tool message is valid iff its toolCallId is
        // non-blank, has an owning assistant, that owner appears BEFORE it (a result that precedes
        // its own tool_call is as invalid to the provider as an orphan), and it is the FIRST valid
        // result for that call id in document order (a duplicate would resolve the same
        // tool_use_id twice and be rejected). Decisions are recorded in a bitmap and applied
        // high-to-low so list indices stay stable during removal.
        //
        // CRITICAL FIX: We no longer rely on callIdToOwner (which only keeps the LATEST owner).
        // Instead we scan backwards from each tool message to find the nearest preceding assistant
        // that owns this callId. This prevents valid tool results from earlier turns being
        // incorrectly dropped when the same callId is reused later (common with Kimi's
        // "<tool_name>:<index>" format).
        boolean[] dropTool = new boolean[msgs.size()];
        Set<String> seenPairs = new HashSet<>();
        for (int i = 0; i < msgs.size(); i++) {
            ChatMessage m = msgs.get(i);
            if (!"tool".equals(m.getRole())) {
                continue;
            }
            String tcid = m.getToolCallId();
            if (tcid == null || tcid.isBlank()) {
                dropTool[i] = true;
                orphanDropped++;
                continue;
            }
            Integer owner = null;
            for (int j = i - 1; j >= 0; j--) {
                ChatMessage a = msgs.get(j);
                if ("assistant".equals(a.getRole()) && a.getToolCalls() != null) {
                    for (ToolCall tc : a.getToolCalls()) {
                        if (tc != null && tcid.equals(tc.getId())) {
                            owner = j;
                            break;
                        }
                    }
                    if (owner != null) {
                        break;
                    }
                }
            }
            if (owner == null) {
                dropTool[i] = true;
                orphanDropped++;
                continue;
            }
            String pair = owner + ":" + tcid;
            if (!seenPairs.add(pair)) {
                dropTool[i] = true;
                orphanDropped++;
            }
        }
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (dropTool[i]) {
                msgs.remove(i);
            }
        }

        // Pass 3: synthesise placeholders for any call id that has no valid tool result. Owner
        // positions are recomputed against the post-removal list because Pass 2 removals shifted
        // indices. Recompute valid pairs too; carrying original indices across removals can make
        // an existing tool result look missing and insert a duplicate placeholder.
        Set<String> currentPairs = new HashSet<>();
        for (int i = 0; i < msgs.size(); i++) {
            ChatMessage m = msgs.get(i);
            if (!"tool".equals(m.getRole())) {
                continue;
            }
            String tcid = m.getToolCallId();
            if (tcid == null || tcid.isBlank()) {
                continue;
            }
            Integer owner = nearestOwner(msgs, i, tcid);
            if (owner != null) {
                currentPairs.add(owner + ":" + tcid);
            }
        }

        List<Map.Entry<Integer, String>> missing = new ArrayList<>();
        for (int i = 0; i < msgs.size(); i++) {
            ChatMessage m = msgs.get(i);
            if ("assistant".equals(m.getRole()) && m.getToolCalls() != null) {
                for (ToolCall tc : m.getToolCalls()) {
                    if (tc != null && tc.getId() != null && !tc.getId().isBlank()) {
                        String pair = i + ":" + tc.getId();
                        if (!currentPairs.contains(pair)) {
                            missing.add(Map.entry(i, tc.getId()));
                        }
                    }
                }
            }
        }
        missing.sort((a, b) -> Integer.compare(b.getKey(), a.getKey()));

        int inserted = 0;
        for (Map.Entry<Integer, String> e : missing) {
            int ownerIdx = e.getKey();
            String callId = e.getValue();
            // Insert directly AFTER the owning assistant message. If multiple call ids are missing
            // from the SAME assistant message, the reverse-order loop will produce them in
            // call-list order on disk (because we keep inserting at ownerIdx+1, pushing the
            // previous placeholder one slot further down \u2014 net order is preserved).
            int insertAt = Math.min(ownerIdx + 1, msgs.size());
            msgs.add(insertAt, ChatMessage.tool(callId, MISSING_PLACEHOLDER, true));
            inserted++;
        }

        SweepResult result = new SweepResult(inserted, orphanDropped);
        if (!result.isClean()) {
            log.info("history-sweep thread={} inserted={} dropped={} (total messages now {})", thread.getId(), inserted,
                orphanDropped, msgs.size());
        }
        return result;
    }

    private static Integer nearestOwner(List<ChatMessage> msgs, int beforeIndex, String toolCallId) {
        for (int j = beforeIndex - 1; j >= 0; j--) {
            ChatMessage a = msgs.get(j);
            if ("assistant".equals(a.getRole()) && a.getToolCalls() != null) {
                for (ToolCall tc : a.getToolCalls()) {
                    if (tc != null && toolCallId.equals(tc.getId())) {
                        return j;
                    }
                }
            }
        }
        return null;
    }
}
