package com.harnex.web.api.tool;

import java.util.Map;
import java.util.Optional;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.harnex.config.ProjectRuntime;
import com.harnex.tool.budget.BudgetEnforcer;
import com.harnex.util.Ids;

/**
 * Lazy-fetch endpoint for persisted tool results. When BudgetEnforcer truncates a tool output, it stamps the
 * assistant-visible content with a {@code ref:<sha>} marker; the UI uses this endpoint to pull back the full payload on
 * demand.
 *
 * <p>
 * Path-traversal hardened: thread id must pass {@link Ids#isSafe} and ref must be a 16-hex SHA prefix; both checks
 * happen inside {@link BudgetEnforcer#readPersisted}.
 */
@RestController
@ProjectRuntime
@RequestMapping("/api/tools/result")
public class ToolResultController {

    private final BudgetEnforcer budget;

    public ToolResultController(BudgetEnforcer budget) {
        this.budget = budget;
    }

    @GetMapping(value = "/{threadId}/{ref}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> get(@PathVariable String threadId, @PathVariable String ref) {
        Optional<String> body = budget.readPersisted(threadId, ref);
        return body.<ResponseEntity<String>>map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.status(404).body("not found"));
    }

    /** Lightweight existence + length probe so the UI can decide whether to render an "expand" affordance. */
    @GetMapping(value = "/{threadId}/{ref}/meta", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> meta(@PathVariable String threadId, @PathVariable String ref) {
        Optional<String> body = budget.readPersisted(threadId, ref);
        return body.map(s -> ResponseEntity.ok(Map.<String, Object>of("exists", true, "chars", s.length())))
            .orElseGet(() -> ResponseEntity.status(404).body(Map.of("exists", false)));
    }
}