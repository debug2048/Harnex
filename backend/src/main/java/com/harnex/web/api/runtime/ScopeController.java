package com.harnex.web.api.runtime;

import org.springframework.web.bind.annotation.*;
import com.harnex.config.AgentConfig;
import com.harnex.config.RuntimeDomainPaths;
import com.harnex.config.RuntimeProfile;
import com.harnex.execution.ScopeIdentity;

import io.swagger.v3.oas.annotations.media.Schema;

@RestController
@RequestMapping("/api/v1")
public final class ScopeController {
    private final ScopeIdentity identity;
    private final AgentConfig cfg;
    public ScopeController(ScopeIdentity identity, AgentConfig cfg) { this.identity = identity; this.cfg = cfg; }
    @GetMapping("/scope")
    public ScopeView scope() { return new ScopeView(identity.scopeId(), identity.instanceId(), identity.profile(),
        RuntimeDomainPaths.id(cfg), identity.projectRoot() == null ? null : identity.projectRoot().toString()); }
    @Schema(requiredProperties = {"scopeId", "instanceId", "runtimeProfile", "runtimeDomainId"})
    public record ScopeView(String scopeId, String instanceId, RuntimeProfile runtimeProfile,
        String runtimeDomainId, String projectRoot) {}

}
