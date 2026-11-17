package com.harnex.run;

import java.util.LinkedHashMap;
import java.util.Map;

import com.harnex.core.StepResult;
import com.harnex.model.StreamEvent;

/** Projects the event stream into the compact run facts exposed by the Local API and durable ledger. */
final class RunFactProjector {

    private RunFactProjector() {}

    static void project(RunHandle run, StreamEvent event) {
        Map<String, Object> data = event.getData() == null ? Map.of() : event.getData();
        boolean subagentLifecycle = "subagent_started".equals(event.getType())
            || "subagent_finished".equals(event.getType());
        boolean subagentEvent = subagentLifecycle || "subagent_event".equals(event.getType());
        if (subagentEvent) {
            run.updateSubagent(event.getType(), data, event.getSeq(), event.getTs());
        }
        if (data.containsKey("activityKind") && !subagentLifecycle) {
            Map<String, Object> activity = new LinkedHashMap<>();
            activity.put("seq", event.getSeq());
            activity.put("ts", event.getTs());
            activity.put("eventType", event.getType());
            copy(data, activity, "activityKind", "activityTitle", "activitySummary", "activityTargets",
                "activityOutcome", "id", "callId", "name", "toolName", "subagentThreadId",
                "parentThreadId", "delegateCallId", "childEventType", "from", "to", "reason", "selectedAttempts",
                "circuitState", "actualProvider", "actualModel", "trigger", "outcome", "summarySource",
                "beforeTokens", "afterTokens", "reductionPercent", "sizeEffective", "purpose");
            run.addActivity(activity);
        }
        switch (event.getType()) {
            case "agent_step_result" -> {
                if (data.get("result") instanceof StepResult result) run.recordStepResult(result);
            }
            case "subagent_event" -> {
                if ("utility_model_start".equals(data.get("childEventType"))) run.goalModelStarted();
                if ("message_start".equals(data.get("childEventType"))) {
                    run.subagentMessageStarted(text(data, "provider", "actualProvider"),
                        text(data, "model", "actualModel"), event.getSeq(), event.getTs(), text(data, "subagentThreadId"));
                }
                if ("tool_use_start".equals(data.get("childEventType"))) run.goalToolStarted();
                if ("provider_switched".equals(data.get("childEventType"))) {
                    run.subagentProviderSwitched(text(data, "subagentThreadId"), text(data, "from"),
                        text(data, "reason"), event.getTs());
                }
            }
            case "message_start" -> run.messageStarted(text(data, "provider", "actualProvider"),
                text(data, "model", "actualModel"), event.getSeq(), event.getTs());
            case "utility_model_start" -> run.goalModelStarted();
            case "tool_use_start" -> run.goalToolStarted();
            case "provider_switched" -> run.providerSwitched(text(data, "from"),
                text(data, "to", "actualProvider"), text(data, "actualModel", "model"),
                text(data, "reason"), (int)number(data.get("selectedAttempts")),
                text(data, "circuitState"), event.getTs());
            case "usage" -> run.addUsage(number(data.get("prompt")), number(data.get("completion")),
                number(data.get("total")), number(data.get("cachedInput")), number(data.get("cacheWriteInput")),
                number(data.get("reasoning")), text(data, "provider"), text(data, "model"), text(data, "kind"),
                Boolean.TRUE.equals(data.get("estimated")), Boolean.FALSE.equals(data.get("reportedComplete")),
                event.getTs(), text(data, "subagentThreadId"));
            case "run_control_checkpoint" -> run.updateCheckpointPhase(checkpointPhase(text(data, "phase")));
            case "run_input_applied" -> run.steeringApplied(text(data, "stepId"));
            default -> {
                // Most events are retained in the replay buffer but do not add compact facts.
            }
        }
    }

    private static RunRecord.StepPhase checkpointPhase(String value) {
        if (value == null || value.isBlank()) return RunRecord.StepPhase.CHECKPOINT;
        try {
            return RunRecord.StepPhase.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return RunRecord.StepPhase.CHECKPOINT;
        }
    }

    private static long number(Object value) {
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private static String text(Map<String, Object> data, String... keys) {
        for (String key : keys) {
            Object value = data.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return "";
    }

    private static void copy(Map<String, Object> source, Map<String, Object> target, String... keys) {
        for (String key : keys) {
            if (source.containsKey(key)) target.put(key, source.get(key));
        }
    }
}
