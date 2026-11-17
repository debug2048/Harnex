package com.harnex.tool;

import java.util.*;

import org.springframework.stereotype.Component;

import com.harnex.config.ProjectRuntime;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@ProjectRuntime
public class ToolRegistry {

    private final List<Tool> tools;
    private final Map<String, Tool> byName = new LinkedHashMap<>();

    public ToolRegistry(List<Tool> tools) {
        this.tools = tools;
    }

    @PostConstruct
    public void index() {
        for (Tool t : tools) {
            byName.put(t.getName(), t);
        }
        log.info("ToolRegistry indexed {} tools: {}", byName.size(), byName.keySet());
    }

    public synchronized Tool get(String name) {
        return byName.get(name);
    }

    public synchronized Collection<Tool> all() {
        // Snapshot copy: callers must be able to iterate without an external lock even when
        // McpRegistry's reconnect path is concurrently re-registering tools.
        return new ArrayList<>(byName.values());
    }

    /** Dynamically register a tool (e.g. MCP tools discovered at runtime). */
    public synchronized void register(Tool tool) {
        Tool prev = byName.put(tool.getName(), tool);
        if (prev != null && prev != tool) {
            log.warn("Tool '{}' re-registered \u2014 previous implementation ({}) replaced by {}", tool.getName(),
                prev.getClass().getSimpleName(), tool.getClass().getSimpleName());
        } else {
            log.info("Dynamically registered tool: {}", tool.getName());
        }
    }

    /**
     * Remove a previously registered tool by name. Used by {@code McpRegistry} during reconnect / server shutdown so
     * dead adapters (whose underlying transport is closed) cannot be dispatched by the agent loop. Returns {@code true}
     * if a tool was actually removed.
     */
    public synchronized boolean unregister(String name) {
        Tool prev = byName.remove(name);
        if (prev != null) {
            log.info("Unregistered tool: {} ({})", name, prev.getClass().getSimpleName());
            return true;
        }
        return false;
    }

    public List<ToolDefinition> definitionsFor(Set<String> allowed) {
        // Snapshot under the write lock so an MCP reconnect that calls register/unregister
        // mid-iteration cannot trigger ConcurrentModificationException or hand the agent loop
        // a partially-rebuilt tool table. The snapshot is small (typically <30 entries) so the
        // copy cost is negligible compared to the LLM call that follows.
        List<Tool> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(byName.values());
        }
        List<ToolDefinition> out = new ArrayList<>();
        for (Tool t : snapshot) {
            if (!allowed.contains(t.getName())) {
                continue;
            }
            // Capability gate (Tool.available() SPI): external-dependency tools (e.g. web_search
            // with no API key) advertise themselves as unavailable so the model never sees an
            // un-callable tool. Default-true for every ordinary tool, so this is a no-op for them.
            // Must be a pure function of static config — see Tool#available() cache-stability note.
            if (!t.available()) {
                continue;
            }
            ToolDefinition d = t.getDefinition();
            // Auto-fill capability hints if the Tool didn't set them on its definition.
            if (d != null) {
                if (d.getCapabilities() == null) {
                    d.setCapabilities(t.capabilities());
                }
                if (d.getTimeoutMs() == null) {
                    d.setTimeoutMs(t.timeoutMillis());
                }
                out.add(d);
            }
        }
        // Spring discovery order and MCP reconnect order are not semantic tool priority. Keep the
        // advertised schema prefix identical when the effective toolset is unchanged.
        out.sort(Comparator.comparing(ToolDefinition::getName));
        return out;
    }
}
