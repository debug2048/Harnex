package com.harnex.web.api.memory;

import java.io.IOException;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.harnex.config.ProjectRuntime;
import com.harnex.memory.CoreMemory;

/** Layered user/workspace memory control plane. */
@RestController
@ProjectRuntime
@RequestMapping("/api/v1/memory")
public class MemoryController {

    private final CoreMemory memory;

    public MemoryController(CoreMemory memory) {
        this.memory = memory;
    }

    @GetMapping
    public Map<String, Object> read() {
        return memory.sources();
    }

    @PutMapping
    public ResponseEntity<?> write(@RequestBody Map<String, Object> body) {
        try {
            memory.writeScoped(String.valueOf(body.getOrDefault("scope", "")),
                String.valueOf(body.getOrDefault("kind", "")),
                String.valueOf(body.getOrDefault("content", "")));
            return ResponseEntity.ok(Map.of("saved", true, "memory", memory.sources()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", Map.of("code", "bad_request", "message", e.getMessage())));
        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", Map.of("code", "memory_write_failed", "message", e.getMessage())));
        }
    }
}
