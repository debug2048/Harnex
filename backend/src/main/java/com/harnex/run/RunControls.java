package com.harnex.run;

import java.util.Map;
import com.harnex.contract.goal.GoalApiRequests;

/** Optional control contract for executions that retain resumable steps. */
public interface RunControls {
    ControlResult cancel(String runId, GoalApiRequests.Control request);
    ControlResult pause(String runId, GoalApiRequests.Control request);
    ControlResult resume(String runId, GoalApiRequests.Control request);
    ControlResult review(String runId, GoalApiRequests.Review request);

    public record ControlResult(RunHandle run, Map<String, Object> view, boolean replayed, String rejectCode,
        String rejectMessage) {
        public static ControlResult accepted(RunHandle run) {
            return new ControlResult(run, run == null ? Map.of() : run.view(), false, null, null);
        }
        public static ControlResult accepted(RunHandle run, Map<String, Object> view, boolean replayed) {
            return new ControlResult(run, view, replayed, null, null);
        }
        public static ControlResult rejected(String code, String message) {
            return new ControlResult(null, Map.of(), false, code, message == null ? "" : message);
        }
        public boolean accepted() { return run != null; }
    }

    public static final class ControlFailure extends IllegalArgumentException {
        private final String code;
        public ControlFailure(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }
}
