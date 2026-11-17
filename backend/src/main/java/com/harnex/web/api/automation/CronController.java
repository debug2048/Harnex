package com.harnex.web.api.automation;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.harnex.cron.AgentCronScheduler;
import com.harnex.cron.AgentCronScheduler.CronJob;
import com.harnex.cron.AutomationExecutionService;
import com.harnex.cron.AutomationRuntimeDirectory;
import com.harnex.cron.AutomationStore;
import com.harnex.gateway.im.ImTaskNotifications;

@RestController
@RequestMapping("/api/cron")
public class CronController {
    private final AgentCronScheduler cron;
    public CronController(AgentCronScheduler cron) { this.cron = cron; }

    @GetMapping
    public List<AgentCronScheduler.CronJobView> cronList() {
        return cron.views();
    }

    @PostMapping
    public ResponseEntity<?> cronAdd(@RequestBody CronJob j) {
        // C3 (P2): the upsert path validates cron expression syntax, allowed-toolsets membership,
        // and time-zone parseability — all surface as IllegalArgumentException / IllegalStateException.
        // A 500 here makes operators chase phantom server errors when the real issue is a typo in
        // their CRON payload. Map both to 400 with the underlying message so the WebUI can render it.
        try {
            return ResponseEntity.ok(Map.of("id", cron.upsert(j)));
        } catch (IllegalArgumentException | IllegalStateException badInput) {
            return ResponseEntity.badRequest().body(Map.of("error", "cron_invalid", "message", badInput.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> cronDel(@PathVariable String id) {
        return Map.of("cancelled", cron.cancel(id));
    }

    public record ScheduleRequest(String cron, String timeZone) {}
    public record EnabledRequest(boolean enabled) {}

    @PostMapping("/preview")
    public AgentCronScheduler.SchedulePreview cronPreview(@RequestBody ScheduleRequest request) {
        return AgentCronScheduler.preview(request.cron(), request.timeZone());
    }

    @PostMapping("/{id}/enabled")
    public Map<String, Object> cronEnabled(@PathVariable String id, @RequestBody EnabledRequest request) {
        cron.setEnabled(id, request.enabled());
        return Map.of("id", id, "enabled", request.enabled());
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<?> cronRun(@PathVariable String id) {
        cron.runNow(id);
        return ResponseEntity.accepted().body(Map.of("id", id, "accepted", true));
    }

    @ExceptionHandler(AutomationStore.StorageException.class)
    public ResponseEntity<?> cronStorageError(AutomationStore.StorageException failure) {
        return ResponseEntity.status(503).body(Map.of("error", "cron_persistence_failed", "message", failure.getMessage()));
    }

    @GetMapping("/targets")
    public List<AutomationRuntimeDirectory.TargetView> targets() { return cron.targets(); }

    @GetMapping("/toolsets")
    public Map<String, List<String>> toolsets() { return cron.toolsets(); }
    @GetMapping("/targets/{targetId}/notifications")
    public ImTaskNotifications.SettingsView notificationSettings(@PathVariable String targetId) {
        return cron.notificationSettings(targetId);
    }

    @GetMapping("/{id}/history")
    public List<AutomationStore.Attempt> history(@PathVariable String id) { return cron.history(id); }

    @GetMapping("/results/{attemptId}")
    public ResponseEntity<AutomationExecutionService.Result> result(@PathVariable String attemptId) {
        var result = cron.result(attemptId);
        return result == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(result);
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Boolean> cancelExecution(@PathVariable String id) {
        cron.cancelExecution(id); return Map.of("requested", true);
    }

    @ExceptionHandler(AgentCronScheduler.JobBusyException.class)
    public ResponseEntity<?> cronBusy(AgentCronScheduler.JobBusyException failure) {
        return ResponseEntity.status(409).body(Map.of("error", "cron_busy", "message", failure.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<?> cronMissing(NoSuchElementException failure) {
        return ResponseEntity.status(404).body(Map.of("error", "cron_not_found", "message", failure.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> cronInvalid(IllegalArgumentException failure) {
        return ResponseEntity.badRequest().body(Map.of("error", "cron_invalid", "message", failure.getMessage()));
    }
}
