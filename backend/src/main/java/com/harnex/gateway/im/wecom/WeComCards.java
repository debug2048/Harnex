package com.harnex.gateway.im.wecom;

import com.harnex.gateway.im.ImInteractionCard;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** WeCom card layout and platform text limits. */
final class WeComCards {
    private WeComCards() {}
    static Map<String, Object> body(ImInteractionCard card) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("card_type", card.pending() ? "button_interaction" : "text_notice");
        body.put("task_id", card.id()); body.put("source", Map.of("desc", "Harnex"));
        body.put("main_title", Map.of("title", brief(card.title(), 100), "desc", brief(card.pending() ? card.deadlineText() : card.stateLabel(), 300)));
        body.put("sub_title_text", brief(card.summary().replaceAll("(?m)^`{3,}\\s*$", ""), 900));
        body.put("horizontal_content_list", List.of(Map.of("keyname", "发起人", "value", brief(card.owner(), 160)),
            Map.of("keyname", "工作区", "value", brief(card.workspace(), 160)), Map.of("keyname", "请求编号", "value", card.code())));
        if (card.pending()) body.put("button_list", card.buttons().stream().map(button -> Map.of("text", button.label(),
            "key", card.id() + ":" + button.action(), "style", "approve".equals(button.action()) ? 1 : 2)).toList());
        return body;
    }
    private static String brief(String text, int bytes) {
        if (text.getBytes(StandardCharsets.UTF_8).length <= bytes) return text;
        StringBuilder value = new StringBuilder(); int size = 0;
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset); String next = new String(Character.toChars(cp));
            int length = next.getBytes(StandardCharsets.UTF_8).length;
            if (size + length > bytes - 35) break;
            value.append(next); size += length; offset += Character.charCount(cp);
        }
        return value + "…请查看详情";
    }
}
