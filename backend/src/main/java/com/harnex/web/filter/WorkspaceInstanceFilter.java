package com.harnex.web.filter;

import java.io.IOException;
import java.util.Map;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.execution.ScopeIdentity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Rejects a request stamped for an old workspace JVM before it can mutate the current instance. */
@Component
@ConditionalOnBean(ScopeIdentity.class)
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class WorkspaceInstanceFilter extends OncePerRequestFilter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ScopeIdentity identity;

    public WorkspaceInstanceFilter(ScopeIdentity identity) {
        this.identity = identity;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        // Cached bodies may survive a JVM restart; their response identity must not cross instances or scopes.
        response.addHeader(HttpHeaders.VARY, "X-Harnex-Scope-Id, X-Harnex-Instance-Id");
        String workspaceId = request.getHeader("X-Harnex-Scope-Id");
        String instanceId = request.getHeader("X-Harnex-Instance-Id");
        boolean staleWorkspace = workspaceId != null && !workspaceId.isBlank()
            && !identity.scopeId().equals(workspaceId);
        boolean staleInstance = instanceId != null && !instanceId.isBlank()
            && !identity.instanceId().equals(instanceId);
        if (staleWorkspace || staleInstance) {
            response.setStatus(409);
            response.setContentType("application/json;charset=UTF-8");
            JSON.writeValue(response.getOutputStream(), Map.of(
                "error", Map.of("code", "runtime_instance_changed",
                    "message", "the target Scope Runtime instance is no longer active"),
                "scopeId", identity.scopeId(),
                "instanceId", identity.instanceId()));
            return;
        }
        response.setHeader("X-Harnex-Scope-Id", identity.scopeId());
        response.setHeader("X-Harnex-Instance-Id", identity.instanceId());
        chain.doFilter(request, response);
    }
}
