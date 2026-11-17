package com.harnex.web.api.config;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.harnex.config.ConfigControlService;
import com.harnex.config.ConfigControlService.RevisionConflictException;
import com.harnex.provider.ProviderProbeService;

/** Revisioned user configuration API. Workspace state uses dedicated Memory/Skill/Automation APIs. */
@RestController
@RequestMapping("/api/v1/config")
public class ConfigController {

    private final ConfigControlService config;
    private final ProviderProbeService providerProbe;

    public ConfigController(ConfigControlService config, ProviderProbeService providerProbe) {
        this.config = config;
        this.providerProbe = providerProbe;
    }

    @GetMapping("/schema")
    public Map<String, Object> schema() {
        return config.schema();
    }

    @GetMapping
    public ResponseEntity<?> read(@RequestParam(defaultValue = "user") String scope) {
        try {
            return ResponseEntity.ok(config.read(scope));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error("bad_request", e.getMessage()));
        }
    }

    @GetMapping("/providers/{providerId}/api-key")
    public ResponseEntity<?> revealProviderApiKey(@PathVariable String providerId) {
        try {
            return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("apiKey", config.revealProviderApiKey(providerId)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error("bad_request", e.getMessage()));
        }
    }

    @GetMapping("/image-services/{serviceId}/api-key")
    public ResponseEntity<?> revealImageServiceApiKey(@PathVariable String serviceId) {
        try {
            return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("apiKey", config.revealImageServiceApiKey(serviceId)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error("bad_request", e.getMessage()));
        }
    }

    @PutMapping
    public ResponseEntity<?> save(@RequestBody Map<String, Object> body) {
        try {
            String scope = String.valueOf(body.getOrDefault("scope", "user"));
            String revision = String.valueOf(body.getOrDefault("revision", ""));
            @SuppressWarnings("unchecked")
            Map<String, Object> values = body.get("values") instanceof Map<?, ?> map
                ? (Map<String, Object>)map : Map.of();
            Set<String> credentialChanges = body.get("credentialChanges") instanceof List<?> changes
                ? changes.stream().map(String::valueOf).collect(Collectors.toSet()) : Set.of();
            return ResponseEntity.ok(config.save(scope, revision, values, credentialChanges));
        } catch (RevisionConflictException e) {
            return ResponseEntity.status(409).body(Map.of("error", Map.of("code", "revision_conflict",
                "message", e.getMessage(), "currentRevision", e.currentRevision())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error("config_invalid", e.getMessage()));
        }
    }

    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> values = body.get("values") instanceof Map<?, ?> map
            ? (Map<String, Object>)map : body;
        List<String> errors = config.validate(values);
        return Map.of("valid", errors.isEmpty(), "errors", errors);
    }

    @PostMapping("/test")
    public ResponseEntity<?> test(@RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(config.testEndpoint(String.valueOf(body.getOrDefault("kind", "provider")),
                String.valueOf(body.getOrDefault("url", ""))));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error("bad_request", e.getMessage()));
        }
    }

    /** Real, billable inference probe against the exact unsaved provider candidate; never routes or persists. */
    @PostMapping("/providers/probe")
    public ResponseEntity<?> probeProvider(@RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> values = body.get("values") instanceof Map<?, ?> map
                ? (Map<String, Object>)map : Map.of();
            return ResponseEntity.ok(providerProbe.probe(
                String.valueOf(body.getOrDefault("providerId", "")),
                body.get("model") == null ? null : String.valueOf(body.get("model")), values));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error("bad_request", e.getMessage()));
        }
    }

    /** Non-billable model discovery against the exact unsaved provider endpoint/key candidate. */
    @PostMapping("/providers/models")
    public ResponseEntity<?> providerModels(@RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> values = body.get("values") instanceof Map<?, ?> map
                ? (Map<String, Object>)map : Map.of();
            return ResponseEntity.ok(providerProbe.models(
                String.valueOf(body.getOrDefault("providerId", "")), values));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error("model_catalogue_unavailable", e.getMessage()));
        }
    }

    @PostMapping("/reload")
    public Map<String, Object> reload() {
        return config.reloadSettings();
    }

    private static Map<String, Object> error(String code, String message) {
        return Map.of("error", Map.of("code", code, "message", message == null ? "" : message));
    }
}
