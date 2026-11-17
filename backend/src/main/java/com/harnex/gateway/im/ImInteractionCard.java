package com.harnex.gateway.im;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harnex.safety.SecretRedactor;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Provider-independent, immutable interaction presentation; the executor remains the authority for every decision. */
public record ImInteractionCard(String id, String code, String kind, String title, String summary, String details,
    String owner, String workspace, long expiresAt, List<Button> buttons, String state, int revision) {
    public record Button(String action, String label) {}
    private static final ObjectMapper JSON = new ObjectMapper();
    public boolean pending() { return "PENDING".equals(state); }
    public String stateLabel() {
        return switch (state) {
            case "APPROVED" -> "已批准本次操作"; case "DENIED" -> "已拒绝";
            case "ANSWERED" -> "已提交回答"; case "CANCELLED" -> "任务已取消";
            case "EXPIRED" -> "已过期，未批准操作"; case "RESTARTED" -> "服务已重启，请重新发送任务";
            case "CLOSED" -> "已在其他客户端处理或已结束"; default -> "等待你的处理";
        };
    }
    public ImInteractionCard withState(String next) {
        return new ImInteractionCard(id, code, kind, title, summary, details, owner, workspace, expiresAt,
            List.of(), next, revision + 1);
    }
    public String deadlineText() {
        String time = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss xxx").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(expiresAt));
        // A platform card is not a ticking clock: a fixed deadline must not look like a stale live countdown.
        return pending() ? "截止 " + time + "，超时自动失效" : stateLabel();
    }
    public String body() {
        return "发起人：" + flat(owner) + "\n工作区：" + flat(workspace) + "\n\n" + summary + "\n\n" + deadlineText()
            + (pending() ? "\n仅发起人可处理；一次同意只授权提示中的本次操作。" : "\n旧按钮已失效，不会重复执行。");
    }
    public String text() {
        String help = !pending() ? "" : "question".equals(kind)
            ? "\n\n这里只有一个待回答问题时，可直接回复选项编号、选项文字或你的回答。\n也可回复 /回答 " + code + " 内容。"
            : "\n\n/同意 " + code + "\n/拒绝 " + code;
        return title + " · " + code + "\n" + body() + help
            + (pending() ? "\n/详情 " + code + " 查看完整操作；/取消 停止任务。" : "");
    }
    public static ImInteractionCard create(String id, String code, String kind, Map<String, Object> payload,
        ImTypes.Inbound source, String workspace, long expiresAt) {
        String title;
        String summary;
        String details;
        List<Button> buttons = new ArrayList<>();
        if ("question".equals(kind)) {
            title = "需要你回答一个问题";
            summary = String.valueOf(payload.getOrDefault("question", ""));
            if (payload.get("options") instanceof List<?> options) {
                for (int i = 0; i < options.size(); i++) {
                    summary += "\n" + (i + 1) + ". " + options.get(i);
                    if (i < 4) buttons.add(new Button("answer:" + (i + 1), "选 " + (i + 1)));
                }
            }
            details = summary;
        } else if ("plan_review".equals(kind)) {
            title = "确认执行计划"; details = String.valueOf(payload.getOrDefault("plan", "")); summary = preview(details, 1500);
            buttons.add(new Button("approve", "同意计划")); buttons.add(new Button("deny", "退回计划"));
        } else {
            String tool = String.valueOf(payload.getOrDefault("toolName", ""));
            Object raw = payload.getOrDefault("arguments", "{}");
            JsonNode args;
            try { args = raw instanceof String text ? JSON.readTree(text) : JSON.valueToTree(raw); }
            catch (Exception malformed) { args = JSON.createObjectNode(); }
            if (args == null) args = JSON.createObjectNode();
            hideCredentials(args);
            String path = flat(args.path("path").asText(args.path("file_path").asText("未指定")));
            switch (tool) {
                case "file_write" -> {
                    title = "批准写入文件";
                    String content = args.path("content").asText();
                    summary = "文件：" + path + "\n将写入 " + content.length() + " 个字符；已有文件可能被覆盖。\n内容预览：\n" + block(preview(content, 700));
                }
                case "file_edit", "multi_edit" -> {
                    title = "批准修改文件"; summary = "文件：" + path;
                    if (args.has("old_string") || args.has("new_string")) summary += "\n原内容：\n" + block(preview(args.path("old_string").asText(), 450))
                        + "\n替换为：\n" + block(preview(args.path("new_string").asText(), 450));
                    else summary += "\n将执行 " + (args.path("edits").isArray() ? args.path("edits").size() : 1) + " 处修改；可查看完整操作详情。";
                }
                case "bash" -> {
                    title = "批准执行命令";
                    summary = "目录：" + flat(args.path("cwd").asText(workspace)) + "\n命令：\n"
                        + block(preview(args.path("command").asText(args.path("cmd").asText()), 1200));
                }
                case "delegate" -> { title = "批准委派子任务"; summary = preview(args.path("task").asText(args.path("prompt").asText("处理子任务")), 1200); }
                default -> { title = "批准调用工具：" + tool; summary = "请查看操作参数后决定：\n" + block(preview(args.toPrettyString(), 1200)); }
            }
            if (Boolean.TRUE.equals(payload.get("outsideWrite"))) summary = "注意：本次操作涉及工作区以外的写入。\n\n" + summary;
            details = title + "\n完整操作参数：\n" + block(args.toPrettyString());
            buttons.add(new Button("approve", "仅批准本次")); buttons.add(new Button("deny", "拒绝"));
        }
        buttons.add(new Button("details", "查看详情")); buttons.add(new Button("cancel", "取消任务"));
        return new ImInteractionCard(id, code, kind, title, scrub(summary), scrub(details),
            source.userName() == null || source.userName().isBlank() ? source.userId() : source.userName(), workspace,
            expiresAt, List.copyOf(buttons), "PENDING", 1);
    }
    static String preview(String value, int maximum) {
        if (value.length() <= maximum) return value;
        int end = maximum > 0 && Character.isHighSurrogate(value.charAt(maximum - 1)) ? maximum - 1 : maximum;
        return value.substring(0, end) + "\n…内容较长，点击「查看详情」查看完整操作。";
    }
    private static String scrub(String value) { return SecretRedactor.redact(value, false); }
    private static void hideCredentials(JsonNode value) {
        if (value.isObject()) {
            var object = (ObjectNode) value;
            var fields = new ArrayList<String>(); object.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                if (object.path(field).isTextual() && field.toLowerCase(Locale.ROOT).replaceAll("[-_]", "")
                    .matches(".*(password|apikey|accesstoken|authorization|secret|credential).*")) object.put(field, "[敏感值已隐藏]");
                else hideCredentials(object.path(field));
            }
        } else if (value.isArray()) value.forEach(ImInteractionCard::hideCredentials);
    }
    private static String flat(String value) { return value == null ? "" : value.replaceAll("[\\p{Cntrl}<>]", " "); }
    private static String block(String value) {
        int max = 0, current = 0;
        for (char c : value.toCharArray()) { current = c == '`' ? current + 1 : 0; max = Math.max(max, current); }
        String fence = "`".repeat(Math.max(3, max + 1));
        return fence + "\n" + value + "\n" + fence;
    }
}
