package com.harnex.gateway.im.telegram;

import com.harnex.gateway.im.ImInteractionCard;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Plain text plus inline keyboard; callback data contains an opaque card ID and a bounded action. */
final class TelegramCards {
    private TelegramCards() {}
    static String text(ImInteractionCard card) { return clip(card.text(), 4000); }
    static Map<String, Object> keyboard(ImInteractionCard card) {
        List<List<Map<String, String>>> rows = new ArrayList<>();
        if (card.pending()) for (var button : card.buttons()) {
            String data = card.id() + "|" + button.action();
            if (data.getBytes(StandardCharsets.UTF_8).length > 64) continue;
            rows.add(List.of(Map.of("text", button.label(), "callback_data", data)));
        }
        return Map.of("inline_keyboard", rows);
    }
    static String clip(String text, int limit) {
        if (text.length() <= limit) return text;
        int end = limit - 1;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + "…";
    }
}
