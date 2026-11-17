package com.harnex.gateway.im.feishu;

import com.harnex.gateway.im.ImInteractionCard;
import com.harnex.gateway.im.ImTypes.CardResult;
import java.util.*;

/** JSON message cards and callback UI feedback; approval decisions remain in the shared IM service. */
final class FeishuCards {
    private FeishuCards() {}
    static Map<String, Object> body(ImInteractionCard card) {
        List<Object> elements = new ArrayList<>();
        elements.add(Map.of("tag", "div", "text", Map.of("tag", "plain_text", "content",
            "发起人：" + card.owner() + "\n工作区：" + card.workspace() + "\n请求编号：" + card.code())));
        elements.add(Map.of("tag", "markdown", "content", card.summary()));
        elements.add(Map.of("tag", "note", "elements", List.of(Map.of("tag", "plain_text", "content",
            card.deadlineText() + (card.pending() ? "\n仅发起人可处理；一次同意仅授权本次操作。" : "\n旧按钮已失效，不会重复执行。")))));
        if (card.pending()) {
            // Keep rows compact on phones; card IDs and actions are exact values, never list indices.
            for (int first = 0; first < card.buttons().size(); first += 2) {
                var row = card.buttons().subList(first, Math.min(first + 2, card.buttons().size()));
                elements.add(Map.of("tag", "action", "actions", row.stream().map(button -> Map.of(
                    "tag", "button", "text", Map.of("tag", "plain_text", "content", button.label()),
                    "type", "approve".equals(button.action()) ? "primary" : "deny".equals(button.action()) ? "danger" : "default",
                    "value", Map.of("card_id", card.id(), "action", button.action()))).toList()));
            }
        }
        return Map.of("config", Map.of("wide_screen_mode", true, "enable_forward", false, "update_multi", true),
            "header", Map.of("template", card.pending() ? "orange" : "APPROVED".equals(card.state()) || "ANSWERED".equals(card.state()) ? "green" : "grey",
                "title", Map.of("tag", "plain_text", "content", card.title())), "elements", elements);
    }
    static Map<String, Object> response(CardResult result) {
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("toast", Map.of("type", result.accepted() ? "success" : "error", "content", result.message()));
        if (result.card() != null) reply.put("card", Map.of("type", "raw", "data", body(result.card())));
        return reply;
    }
}
