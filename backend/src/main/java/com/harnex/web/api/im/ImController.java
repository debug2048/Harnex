package com.harnex.web.api.im;

import java.io.IOException;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.harnex.config.ProjectRuntime;
import com.harnex.gateway.im.ImConfigStore;
import com.harnex.gateway.im.ImGatewayService;
import com.harnex.gateway.im.ImTaskNotifications;
import com.harnex.gateway.im.ImTypes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Local authenticated control API. Deliberately outside the unauthenticated /api/gateway callback prefix. */
@RestController
@ProjectRuntime
@RequestMapping("/api/im")
public class ImController {
    private final ImGatewayService service;
    public ImController(ImGatewayService service) { this.service = service; }
    @Schema(name = "ImSaveRequest")
    public record SaveRequest(String revision, ImTypes.Connection connection, String credentialId, String credentialRevision) {}
    @Schema(name = "ImPairRequest")
    public record PairRequest(String revision) {}
    @GetMapping public Map<String, Object> view() { return service.view(); }
    public record NotificationSelection(String revision, String recipientId) {}
    @GetMapping("/notifications") public ImTaskNotifications.SettingsView notifications() { return service.notificationSettings(); }
    @PutMapping("/notifications") public ImTaskNotifications.SettingsView notifications(@RequestBody NotificationSelection request) {
        return service.notificationSettings(request.revision(), request.recipientId());
    }
    @Schema(name = "ImSecretResponse")
    public record SecretResponse(String clientSecret) {
        @Override public String toString() { return "ImSecretResponse[redacted]"; }
    }
    @GetMapping("/credentials/{id}/secret")
    public ResponseEntity<SecretResponse> revealSavedSecret(@PathVariable String id, @RequestParam String revision) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new SecretResponse(service.revealSavedSecret(id, revision)));
    }
    @GetMapping("/{channel}/secret")
    public ResponseEntity<SecretResponse> revealSecret(@PathVariable String channel, @RequestParam String revision,
        @RequestParam String clientId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new SecretResponse(service.revealSecret(channel, revision, clientId)));
    }
    @GetMapping(value = "/dingtalk/template", produces = "application/json")
    public ResponseEntity<byte[]> template() throws IOException {
        try (var input = ImController.class.getResourceAsStream("/im/dingtalk-interaction-card.json")) {
            if (input == null) throw new IllegalStateException("卡片模板未打包，请重新构建 Runtime");
            return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=harnex-dingtalk-card.json").body(input.readAllBytes());
        }
    }
    @PutMapping("/{channel}") public Map<String, Object> save(@PathVariable String channel, @RequestBody SaveRequest request) {
        if (request.connection() == null) throw new IllegalArgumentException("connection is required");
        return service.save(channel, request.revision(), request.connection(), request.credentialId(), request.credentialRevision());
    }
    @PostMapping("/{channel}/reconnect") public Map<String, Object> reconnect(@PathVariable String channel) { return service.reconnect(channel); }
    @PostMapping("/pairing/{id}") public Map<String, Object> pair(@PathVariable String id, @RequestBody PairRequest request) {
        return service.approvePair(id, request.revision());
    }
    @ExceptionHandler({ImConfigStore.ConflictException.class, IllegalStateException.class})
    public ResponseEntity<?> conflict(RuntimeException exception) {
        return ResponseEntity.status(409).body(Map.of("error", Map.of("message", exception.getMessage())));
    }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", Map.of("message", exception.getMessage())));
    }
}
