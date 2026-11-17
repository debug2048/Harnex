package com.harnex.gateway.im.dingtalk;

import com.harnex.gateway.im.ImTypes;
import com.harnex.gateway.im.ImInteractionCard;

import com.fasterxml.jackson.databind.JsonNode;
import com.harnex.gateway.im.ImTypes.*;
import java.util.*;
import okhttp3.*;

/** Native interactive-card API, using the same app identity and Stream callback subscription as the robot. */
final class DingTalkCards {
    private final ImTypes.Connection config;
    private final DingTalkApi api;
    DingTalkCards(ImTypes.Connection config, OkHttpClient http, String base) {
        this(config, new DingTalkApi(config, http, base));
    }
    DingTalkCards(ImTypes.Connection config, DingTalkApi api) { this.config = config; this.api = api; }
    boolean send(Inbound target, ImInteractionCard card) throws DeliveryException {
        if (config.cardTemplateId().isBlank() || !Set.of("group", "single").contains(target.chatType())) return false;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cardTemplateId", config.cardTemplateId()); body.put("outTrackId", card.id());
        body.put("cardData", Map.of("cardParamMap", parameters(card))); body.put("callbackType", "STREAM");
        body.put("privateData", Map.of(target.userId(), Map.of("cardParamMap", privateFeedback(false, ""))));
        body.put("userIdType", 1);
        body.put("imGroupOpenSpaceModel", Map.of("supportForward", false));
        body.put("imRobotOpenSpaceModel", Map.of("supportForward", false));
        if ("group".equals(target.chatType())) {
            body.put("openSpaceId", "dtv1.card//IM_GROUP." + target.chatId());
            String label = target.userName() == null || target.userName().isBlank() ? target.userId() : target.userName();
            body.put("imGroupOpenDeliverModel", Map.of("robotCode", config.clientId(), "atUserIds", Map.of(target.userId(), label)));
        } else {
            body.put("openSpaceId", "dtv1.card//IM_ROBOT." + target.userId());
            body.put("imRobotOpenDeliverModel", Map.of("spaceType", "IM_ROBOT"));
        }
        call("POST", "/v1.0/card/instances/createAndDeliver", body); return true;
    }
    boolean update(ImInteractionCard card) throws DeliveryException {
        call("PUT", "/v1.0/card/instances", Map.of("outTrackId", card.id(), "userIdType", 1,
            "cardData", Map.of("cardParamMap", parameters(card)), "cardUpdateOptions", Map.of("updateCardDataByKey", true)));
        return true;
    }
    private void call(String method, String path, Object body) throws DeliveryException {
        JsonNode value = api.call(method, path, body);
        if (value.has("success") && !value.path("success").asBoolean())
            throw new DeliveryException(DeliveryException.Kind.PERMANENT, "平台未接受卡片，请检查模板和应用权限");
        for (JsonNode delivery : value.path("result").path("deliverResults")) {
            if (delivery.has("success") && !delivery.path("success").asBoolean())
                throw new DeliveryException(DeliveryException.Kind.PERMANENT, "卡片已创建但未投递到会话，请检查应用可见范围和机器人权限");
        }
        if (!value.path("success").asBoolean() && !value.path("result").isObject())
            throw new DeliveryException(DeliveryException.Kind.UNKNOWN, "未收到有效的卡片发送确认");
    }
    static Map<String, String> parameters(ImInteractionCard card) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("title", card.title()); values.put("body", card.body()); values.put("statusText", card.stateLabel());
        values.put("code", card.code());
        for (int i = 0; i < 6; i++) {
            boolean show = card.pending() && i < card.buttons().size();
            values.put("button" + i + "Visible", Boolean.toString(show));
            values.put("button" + i + "Text", show ? card.buttons().get(i).label() : "");
            values.put("button" + i + "Action", show ? card.buttons().get(i).action() : "");
        }
        return values;
    }
    static Map<String, Object> callback(CardResult result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("cardUpdateOptions", Map.of("updateCardDataByKey", true, "updatePrivateDataByKey", true));
        // The transport ACK is always 200; the card Toast must use this actor's business result.
        response.put("userPrivateData", Map.of("cardParamMap", privateFeedback(result.accepted(), result.message())));
        if (result.card() != null && !result.card().pending()) response.put("cardData", Map.of("cardParamMap", parameters(result.card())));
        return response;
    }
    private static Map<String, String> privateFeedback(boolean accepted, String message) {
        return Map.of("feedback", message, "actionSucceeded", Boolean.toString(accepted));
    }
}
