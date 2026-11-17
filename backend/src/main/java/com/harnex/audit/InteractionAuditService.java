package com.harnex.audit;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.util.RotatingJsonlLog;

import lombok.extern.slf4j.Slf4j;

/** Bounded recent diagnostics for resolved user interactions; run state remains the authority. */
@Slf4j
@Service
@ProjectRuntime
public class InteractionAuditService {

    private static final ObjectMapper M = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final int MAX_RETURNED = 500;

    private final Path file;

    @Autowired
    public InteractionAuditService(AgentConfig cfg) {
        this(Path.of(cfg.getDataDir()).toAbsolutePath().normalize().resolve("diagnostics/audit").resolve("interactions.jsonl"));
    }

    InteractionAuditService(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    public synchronized void recordResolved(String kind, String threadId, String callId, Map<String, Object> body) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("status", "resolved");
        item.put("kind", kind == null ? "" : kind);
        item.put("threadId", threadId == null ? "" : threadId);
        item.put("callId", callId == null ? "" : callId);
        item.put("resolvedAt", System.currentTimeMillis());
        Map<String, Object> resolution = new LinkedHashMap<>();
        if (body != null) {
            body.forEach((key, value) -> {
                if (!"kind".equals(key) && value != null) {
                    resolution.put(key, value);
                }
            });
        }
        item.put("resolution", resolution);
        try {
            RotatingJsonlLog.append(file, M.writeValueAsString(item));
        } catch (IOException e) {
            log.warn("interaction audit append failed for {}/{}: {}", threadId, callId, e.toString());
        }
    }

    public synchronized List<Map<String, Object>> listResolved(String threadIdOrNull) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (String line : RotatingJsonlLog.recentLines(file)) {
                if (line.isBlank()) {
                    continue;
                }
                Map<String, Object> item = M.readValue(line, MAP_TYPE);
                if (threadIdOrNull == null || threadIdOrNull.equals(String.valueOf(item.get("threadId")))) {
                    out.add(new LinkedHashMap<>(item));
                }
            }
        } catch (IOException e) {
            log.warn("interaction audit read failed: {}", e.toString());
            return List.of();
        }
        Collections.reverse(out);
        return out.size() <= MAX_RETURNED ? out : List.copyOf(out.subList(0, MAX_RETURNED));
    }
}
