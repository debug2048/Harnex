package com.harnex.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.config.RuntimeDomainPaths;
import com.harnex.storage.SharedFileTransaction;
import com.harnex.execution.ScopeIdentity;
import com.harnex.workspace.WorkspacePathResolver;
import com.harnex.auth.BootTokenService;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/** Discoverable attachment information for CLI, bare JAR and container services; contains no credentials. */
@Component
@ProjectRuntime
@Slf4j
public final class RuntimeServicePublication {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AgentConfig cfg;
    private final ScopeIdentity scope;
    private final WorkspacePathResolver paths;
    private final Path file;
    public RuntimeServicePublication(AgentConfig cfg, ScopeIdentity scope, BootTokenService bootToken,
        WorkspacePathResolver paths) {
        this.cfg = cfg; this.scope = scope; this.paths = paths;
        file = RuntimeDomainPaths.resolve(cfg).resolve("service.json");
    }
    @EventListener(ApplicationReadyEvent.class)
    public void publish(ApplicationReadyEvent event) {
        if (!(event.getApplicationContext() instanceof WebServerApplicationContext web)
            || !Files.isRegularFile(file.resolveSibling("boot-token"))) return;
        try {
            var value = new LinkedHashMap<String, Object>();
            value.put("schemaVersion", 1); value.put("runtimeProfile", "PROJECT");
            value.put("runtimeDomainId", RuntimeDomainPaths.id(cfg));
            value.put("scopeId", scope.scopeId()); value.put("instanceId", scope.instanceId());
            // Publish the exact workspace API spelling, including configured aliases and Windows short paths.
            value.put("canonicalRoot", paths.getProjectRoot().toString()); value.put("runtimePid", ProcessHandle.current().pid());
            value.put("endpoint", "http://127.0.0.1:" + web.getWebServer().getPort());
            value.put("protocolVersion", "1.0"); value.put("lifecycle", "READY");
            value.put("bootTokenFile", "boot-token"); value.put("authType", "boot-token");
            value.put("runtimeVersion", RuntimeBuild.version()); value.put("runtimeSha256", RuntimeBuild.sha256());
            value.put("publishedAt", Instant.now().toString());
            SharedFileTransaction.writeAtomically(file, JSON.writeValueAsBytes(value), false, true);
        } catch (Exception failure) { log.warn("Runtime service discovery information could not be published ({})", failure.getClass().getSimpleName()); }
    }
    @PreDestroy public void close() {
        try {
            if (Files.isRegularFile(file) && scope.instanceId().equals(JSON.readTree(file.toFile()).path("instanceId").asText())) Files.delete(file);
        } catch (Exception failure) { log.warn("Runtime service discovery information could not be removed ({})", failure.getClass().getSimpleName()); }
    }
}
