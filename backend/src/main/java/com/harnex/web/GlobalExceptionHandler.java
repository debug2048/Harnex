package com.harnex.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.harnex.artifact.ArtifactException;
import com.harnex.conversation.ConversationException;
import com.harnex.image.ImageServiceException;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * Web-layer cross-cutting exception handler. Lives under {@code web/} (not {@code controller/}) because it is not
 * itself a controller — it advises every controller and intercepts exceptions that escape them. Keeping it adjacent to
 * other cross-cutting web concerns (filters, advices) makes the layering self-documenting.
 *
 * <p>
 * Without this advice, an uncaught exception bubbles into Spring's default Whitelabel error page which exposes class
 * names, package paths and (in dev) full stack traces.
 *
 * <p>
 * Kept intentionally tiny: the five cases below cover everything we have actually observed. Anything more elaborate
 * (per-exception metrics, structured error codes) belongs in the controllers themselves where they have the request
 * context to be useful.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({ConversationException.class, ArtifactException.class,
        ImageServiceException.class})
    public ResponseEntity<Map<String, Object>> handleWorkbench(RuntimeException failure) {
        String code = failure instanceof ConversationException value ? value.code()
            : failure instanceof ArtifactException value ? value.code()
            : ((ImageServiceException) failure).code();
        int status = code.endsWith("not_found") ? 404 : code.contains("deleted") ? 410
            : code.contains("storage_unavailable") ? 503 : code.contains("conflict") || code.equals("submission_started") ? 409 : 400;
        return ResponseEntity.status(status).body(err(code, failure.getMessage()));
    }

    /**
     * Unified error envelope (docs/03 §3.14): every non-2xx body is {@code {"error":{"code","message"}}} so clients
     * branch on a stable {@code code} instead of parsing prose. Controllers returning errors inline follow the same
     * shape.
     */
    private static Map<String, Object> err(String code, String message) {
        return Map.of("error", Map.of("code", code, "message", message == null ? "" : message));
    }

    /** Bad client input (invalid threadId, missing required field, illegal arg). */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        log.debug("400 bad request: {}", e.getMessage());
        return ResponseEntity.badRequest()
            .body(err("bad_request", e.getMessage() == null ? "bad request" : e.getMessage()));
    }

    /** Malformed @RequestBody / @RequestParam validation. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String first = e.getBindingResult().getFieldError() == null ? e.getMessage()
            : e.getBindingResult().getFieldError().getDefaultMessage();
        return ResponseEntity.badRequest().body(err("validation_failed", "validation failed: " + first));
    }

    /**
     * Unparseable / wrong-content-type request body. Without this dedicated handler the exception fell through to
     * {@link #handleAny}, which: 1. Logged a full stack trace at ERROR for what is really a 400-class client mistake,
     * drowning out genuine server failures and tripping log-volume alerts. 2. Returned a generic 500 to the client,
     * hiding the fact that the request itself was malformed (the SPA / curl user couldn\u2019t tell their JSON was bad
     * from a real backend bug). Most common triggers in this codebase: an empty body where one is required (now
     * {@code @RequestBody(required=false)} on auth endpoints), unbalanced braces in a hand-edited curl invocation, and
     * old clients still sending {@code text/plain} for a JSON endpoint. All are operator/client errors \u2192 400.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException e) {
        // DEBUG, not WARN \u2014 a flood of bad JSON from a misconfigured client should not page
        // anyone. Operators investigating a specific 400 can enable DEBUG temporarily.
        log.debug("400 malformed request body: {}", e.getMostSpecificCause().getMessage());
        // Surface a stable, message-stripped reason so we don\u2019t leak Jackson internals
        // (which sometimes echo offending bytes / class names) to the client.
        return ResponseEntity.badRequest().body(err("malformed_body", "malformed request body"));
    }

    /** Required query/form parameter missing \u2192 400 (was 500 via catch-all). */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException e) {
        log.debug("400 missing parameter: {}", e.getMessage());
        return ResponseEntity.badRequest()
            .body(err("missing_parameter", "missing required parameter: " + e.getParameterName()));
    }

    /** Wrong HTTP method on an existing route \u2192 405 (was 500 via catch-all). */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        log.debug("405 method not allowed: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
            .body(err("method_not_allowed", "method not allowed: " + e.getMethod()));
    }

    /** Unknown route — return 404 instead of 500. */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NoHandlerFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(err("not_found", "not found"));
    }

    /**
     * Static resource miss (e.g. favicon.ico, or any path when no SPA dir is configured). Spring MVC raises this for an
     * unmatched resource handler; without a dedicated handler it falls through to {@link #handleAny} and is logged as a
     * noisy 500. A missing file is simply a 404.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException e) {
        log.debug("404 static resource not found: {}", e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(err("not_found", "not found"));
    }

    /** SSE / async timeout — handled silently so it doesn't pollute logs as a 500. */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public void handleAsyncTimeout(AsyncRequestTimeoutException e, HttpServletResponse response) {
        log.debug("async request timeout (sse)");
        // Async timeouts often happen on a run-event stream after the response content type has
        // already been fixed to text/event-stream. Returning a JSON Map here makes Spring try to
        // serialize JSON through the event-stream response and raises HttpMessageNotWritableException.
        if (!response.isCommitted()) {
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        }
    }

    /** Catch-all: log the full stack server-side, return a generic 500 to the client. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAny(Exception e) {
        log.error("unhandled exception in controller", e);
        // Deliberately do NOT return e.getMessage() — provider/database/file-system messages
        // routinely include host paths, internal class names or model URIs that we don't want
        // leaking to a remote client. Operators see the real cause via the log.error above.
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(err("internal_error", "internal server error"));
    }
}
