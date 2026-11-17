package com.harnex.audit;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.config.AgentConfig;
import com.harnex.config.ProjectRuntime;
import com.harnex.run.RunInputRecord;
import com.harnex.util.RotatingJsonlLog;

import lombok.extern.slf4j.Slf4j;

/** Metadata-only workspace audit for Run Input control-plane mutations; user text is deliberately excluded. */
@Slf4j
@Service
@ProjectRuntime
public class RunInputAuditService {

    private static final ObjectMapper M = new ObjectMapper();
    private final Path file;

    @Autowired
    public RunInputAuditService(AgentConfig cfg) {
        this(Path.of(cfg.getDataDir()).toAbsolutePath().normalize().resolve("diagnostics/audit").resolve("run-inputs.jsonl"));
    }

    RunInputAuditService(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    public synchronized void record(String operation, RunInputRecord input) {
        if (input == null) return;
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("operation", operation == null ? "" : operation);
        item.put("runId", input.runId());
        item.put("threadId", input.threadId());
        item.put("inputId", input.inputId());
        item.put("mode", input.mode().name());
        item.put("status", input.status().name());
        item.put("sequence", input.sequence());
        item.put("revision", input.revision());
        item.put("channel", input.channel());
        item.put("ts", System.currentTimeMillis());
        String traceId = MDC.get("traceId");
        if (traceId != null && !traceId.isBlank()) item.put("traceId", traceId);
        try {
            RotatingJsonlLog.append(file, M.writeValueAsString(item));
        } catch (IOException failure) {
            log.warn("run input audit append failed for {}/{}: {}", input.runId(), input.inputId(),
                failure.toString());
        }
    }
}
