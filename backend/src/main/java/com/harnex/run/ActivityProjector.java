package com.harnex.run;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.harnex.model.StreamEvent;
import com.harnex.tool.ToolPurpose;

/**
 * Adds stable semantic activity fields to low-level stream events. The UI may render rich cards for known kinds and a
 * generic card for everything else without parsing provider/tool-specific prose.
 */
public final class ActivityProjector {

    private ActivityProjector() {}

    public static StreamEvent enrich(StreamEvent event) {
        if (event == null || event.getType() == null) {
            return event;
        }
        Map<String, Object> source = event.getData() == null ? Map.of() : event.getData();
        Map<String, Object> data = new LinkedHashMap<>(source);
        if (event.getType().startsWith("tool_use_") || "subagent_event".equals(event.getType())) {
            String purpose = ToolPurpose.normalize(data.get("purpose"));
            if (purpose == null) purpose = ToolPurpose.fromArguments(data.get("arguments"));
            if (purpose != null) data.put("purpose", purpose);
        }
        switch (event.getType()) {
            case "tool_use_queued" -> activity(data, "tool", toolTitle(data), toolSummary(data), targets(data), "waiting");
            case "tool_use_start" -> activity(data, "tool", toolTitle(data), toolSummary(data), targets(data), "running");
            case "tool_use_end" -> activity(data, "tool", toolTitle(data), outcomeSummary(data), targets(data),
                truthy(data.get("error")) ? "failed" : "completed");
            case "approval_required" ->
                activity(data, "approval", "等待操作授权", toolSummary(data), targets(data), "waiting");
            case "user_question" ->
                activity(data, "question", "等待用户回答", text(data, "question", "message"), List.of(), "waiting");
            case "plan_review" ->
                activity(data, "plan_review", "等待计划确认", text(data, "plan"), List.of(), "waiting");
            case "goal_continue" ->
                activity(data, "goal", "目标继续执行", goalSummary(data), List.of(), "running");
            case "provider_switched" -> activity(data, "provider", "模型服务已切换",
                providerSwitchSummary(data), List.of(), "completed");
            case "context_compression_started" -> activity(data, "context_compression", "正在压缩对话历史",
                compressionStartSummary(data), List.of(), "running");
            case "context_compression_finished" -> activity(data, "context_compression",
                compressionFinishTitle(data), compressionFinishSummary(data), List.of(), compressionOutcome(data));
            case "error" ->
                activity(data, "error", "执行失败", text(data, "message", "reason"), List.of(), "failed");
            case "subagent_started" -> activity(data, "subagent", subagentTitle(data),
                subagentStartSummary(data), List.of(), "running");
            case "subagent_event" -> activity(data, "subagent_step", subagentStepTitle(data),
                subagentStepSummary(data), targets(data), subagentStepOutcome(data));
            case "subagent_finished" -> activity(data, "subagent", subagentTitle(data),
                clip(text(data, "output"), 480), List.of(), truthy(data.get("error")) ? "failed" : "completed");
            default -> {
                return event;
            }
        }
        event.setData(data);
        return event;
    }

    private static void activity(Map<String, Object> data, String kind, String title, String summary,
        List<String> targets, String outcome) {
        data.putIfAbsent("activityKind", kind);
        data.putIfAbsent("activityTitle", blank(title) ? "执行活动" : title);
        data.putIfAbsent("activitySummary", summary == null ? "" : summary);
        data.putIfAbsent("activityTargets", targets == null ? List.of() : targets);
        data.putIfAbsent("activityOutcome", outcome);
    }

    private static String toolTitle(Map<String, Object> data) {
        String name = text(data, "name", "toolName");
        return blank(name) ? "执行工具" : name;
    }

    private static String toolSummary(Map<String, Object> data) {
        return clip(text(data, "arguments", "input"), 240);
    }

    private static String outcomeSummary(Map<String, Object> data) {
        return clip(text(data, "content", "message", "error"), 240);
    }

    private static String goalSummary(Map<String, Object> data) {
        return "turn " + text(data, "turnsUsed") + "/" + text(data, "maxTurns") + " · tokens "
            + text(data, "tokensUsed") + "/" + text(data, "tokenBudget");
    }

    private static String compressionStartSummary(Map<String, Object> data) {
        return text(data, "beforeTokens") + " tokens · " + text(data, "trigger");
    }

    private static String providerSwitchSummary(Map<String, Object> data) {
        String from = text(data, "from");
        String to = text(data, "to", "actualProvider");
        long attempts = number(data.get("selectedAttempts"));
        if (attempts <= 0) return from + " 熔断中，已直接切换到 " + to;
        String reason = text(data, "reason");
        return from + " 尝试 " + attempts + " 次后切换到 " + to
            + (blank(reason) ? "" : " · 原因：" + reason);
    }

    private static String compressionFinishTitle(Map<String, Object> data) {
        return switch (compressionOutcome(data)) {
            case "cancelled" -> "对话历史压缩已取消";
            case "failed" -> "对话历史压缩失败";
            default -> "对话历史压缩完成";
        };
    }

    private static String compressionFinishSummary(Map<String, Object> data) {
        String source = text(data, "summarySource");
        String sourceLabel = "model".equals(source) ? "模型摘要"
            : "deterministic".equals(source) ? "确定性摘要" : source;
        String sizes = text(data, "beforeTokens") + " → " + text(data, "afterTokens") + " tokens";
        String reduction = text(data, "reductionPercent");
        return (blank(sourceLabel) ? "" : sourceLabel + " · ") + sizes
            + (blank(reduction) ? "" : " · -" + reduction + "%");
    }

    private static String compressionOutcome(Map<String, Object> data) {
        String outcome = text(data, "outcome", "activityOutcome");
        return blank(outcome) ? "completed" : outcome;
    }

    private static String subagentTitle(Map<String, Object> data) {
        String goal = clip(text(data, "goal"), 100);
        return blank(goal) ? "Subagent" : "Subagent · " + goal;
    }

    private static String subagentStartSummary(Map<String, Object> data) {
        String model = text(data, "model");
        String toolsets = text(data, "toolsets");
        return (blank(model) ? "默认模型" : model) + (blank(toolsets) ? "" : " · " + toolsets);
    }

    private static String subagentStepTitle(Map<String, Object> data) {
        String type = text(data, "childEventType");
        String tool = text(data, "name", "toolName");
        return switch (type) {
            case "tool_use_queued" -> "工具排队 · " + (blank(tool) ? "tool" : tool);
            case "message_start" -> "调用模型";
            case "provider_switched" -> "模型服务已切换";
            case "context_compression_started" -> "正在压缩对话历史";
            case "context_compression_finished" -> "对话历史压缩完成";
            case "tool_use_start" -> "开始工具 · " + (blank(tool) ? "tool" : tool);
            case "tool_use_end" -> "完成工具 · " + (blank(tool) ? "tool" : tool);
            case "error" -> "Subagent 执行失败";
            default -> "执行信息";
        };
    }

    private static String subagentStepSummary(Map<String, Object> data) {
        String type = text(data, "childEventType");
        if ("message_start".equals(type)) {
            return text(data, "provider") + (blank(text(data, "model")) ? "" : " · " + text(data, "model"));
        }
        if ("provider_switched".equals(type)) {
            return providerSwitchSummary(data);
        }
        if ("context_compression_started".equals(type)) {
            return compressionStartSummary(data);
        }
        if ("context_compression_finished".equals(type)) {
            return compressionFinishSummary(data);
        }
        if ("tool_use_queued".equals(type) || "tool_use_start".equals(type)) {
            return clip(text(data, "arguments"), 240);
        }
        return clip(text(data, "content", "message", "reason", "kind"), 240);
    }

    private static String subagentStepOutcome(Map<String, Object> data) {
        String type = text(data, "childEventType");
        if ("tool_use_queued".equals(type)) {
            return "waiting";
        }
        if ("tool_use_start".equals(type) || "message_start".equals(type)
            || "context_compression_started".equals(type)) {
            return "running";
        }
        if ("context_compression_finished".equals(type)) {
            return compressionOutcome(data);
        }
        return "error".equals(type) || truthy(data.get("error")) ? "failed" : "completed";
    }

    private static List<String> targets(Map<String, Object> data) {
        List<String> out = new ArrayList<>();
        Object metadata = data.get("metadata");
        if (metadata instanceof Map<?, ?> map) {
            addTarget(out, map.get("path"));
            addTarget(out, map.get("file"));
            addTarget(out, map.get("target"));
        }
        addTarget(out, data.get("path"));
        addTarget(out, data.get("file"));
        addTarget(out, data.get("target"));
        return out.stream().distinct().toList();
    }

    private static void addTarget(List<String> out, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            out.add(String.valueOf(value));
        }
    }

    private static String text(Map<String, Object> data, String... keys) {
        for (String key : keys) {
            Object value = data.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return "";
    }

    private static String clip(String value, int max) {
        if (value == null || value.length() <= max) {
            return value == null ? "" : value;
        }
        return value.substring(0, max) + "…";
    }

    private static boolean truthy(Object value) {
        return value != null && !String.valueOf(value).isBlank() && !"false".equalsIgnoreCase(String.valueOf(value));
    }

    private static long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
