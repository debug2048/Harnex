package com.harnex.web.api.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.harnex.run.RunCoordinator;

/** Local Runtime lifecycle hooks used by the stable single-workspace Supervisor. */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final RunCoordinator runs;
    private final byte[] supervisorToken;
    private final Runnable shutdown;

    @Autowired
    public AdminController(RunCoordinator runs, ConfigurableApplicationContext context) {
        this(runs, System.getenv("HARNEX_INTERNAL_SUPERVISOR_TOKEN"), () -> {
            Thread worker = new Thread(() -> {
                try { Thread.sleep(300); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                try { context.close(); } finally { System.exit(0); }
            }, "runtime-idle-upgrade-stop");
            worker.setDaemon(false); worker.start();
        });
    }

    AdminController(RunCoordinator runs, String token) {
        this(runs, token, () -> {});
    }
    AdminController(RunCoordinator runs, String token, Runnable shutdown) {
        this.runs = runs;
        this.supervisorToken = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
        this.shutdown = shutdown;
    }

    @PostMapping("/upgrade-stop-idle")
    public ResponseEntity<Map<String, Object>> stopForUpgrade(
        @RequestHeader(value = "X-Harnex-Supervisor-Token", required = false) String token) {
        if (!authorized(token)) return forbidden();
        if (!runs.stopAcceptingIfIdle()) return ResponseEntity.status(409).body(Map.of("error", Map.of(
            "code", "runtime_busy", "message", "Runtime has active work or is already stopping; no task was cancelled")));
        shutdown.run();
        return ResponseEntity.ok(Map.of("stopping", true));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(
        @RequestHeader(value = "X-Harnex-Supervisor-Token", required = false) String token) {
        if (!authorized(token)) {
            return forbidden();
        }
        return ResponseEntity.ok(Map.of("activeRuns", runs.activeRunCount()));
    }

    @PostMapping("/drain")
    public ResponseEntity<Map<String, Object>> drain(
        @RequestHeader(value = "X-Harnex-Supervisor-Token", required = false) String token,
        @RequestParam(defaultValue = "30000") long timeoutMs) {
        if (!authorized(token)) {
            return forbidden();
        }
        return ResponseEntity.ok(runs.drain(Math.min(120_000L, Math.max(0L, timeoutMs))));
    }

    private boolean authorized(String supplied) {
        if (supervisorToken.length == 0 || supplied == null || supplied.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(supervisorToken, supplied.getBytes(StandardCharsets.UTF_8));
    }

    private static ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(403)
            .body(Map.of("error", Map.of("code", "supervisor_only", "message", "internal Supervisor credential required")));
    }
}
