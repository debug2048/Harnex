package com.harnex.web.api.preview;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.harnex.artifact.ArtifactException;
import com.harnex.preview.PreviewService;

import io.swagger.v3.oas.annotations.Hidden;

@RestController
public final class PreviewController {
    private final PreviewService previews;
    public PreviewController(PreviewService previews) { this.previews = previews; }
    @PostMapping("/api/v1/previews") public PreviewService.PreviewSession create(@RequestBody PreviewService.PreviewRequest request) throws IOException {
        return previews.create(request);
    }
    @DeleteMapping("/api/v1/previews/{id}") public void remove(@PathVariable String id) { previews.remove(id); }
    @Hidden // Clients use the opaque URL returned by create; this is not a general file API.
    @GetMapping("/api/v1/preview-content/{scope}/{instance}/{id}/{*path}")
    public ResponseEntity<byte[]> content(@PathVariable String scope, @PathVariable String instance,
        @PathVariable String id, @PathVariable String path) throws IOException {
        var resource = previews.read(scope, instance, id, path.startsWith("/") ? path.substring(1) : path);
        byte[] bytes = resource.bytes();
        if (resource.mime().equals("text/html")) {
            String html = new String(bytes, StandardCharsets.UTF_8);
            // Only report page errors; page interactions stay within the preview.
            String bridge = "<script>" + "(() => {const session='" + id + "';"
                + "const send=(type,text)=>{if(parent!==window)parent.postMessage({type,session,text:String(text).slice(0,16000)},'*')};"
                + "addEventListener('error',e=>send('harnex-preview-error',e.message||'A page resource could not be loaded'),true);"
                + "addEventListener('unhandledrejection',e=>send('harnex-preview-error',e.reason));})();" + "</script>";
            int doctypeEnd = html.stripLeading().toLowerCase(Locale.ROOT).startsWith("<!doctype") ? html.indexOf('>') + 1 : 0;
            bytes = (html.substring(0, doctypeEnd) + bridge + html.substring(doctypeEnd)).getBytes(StandardCharsets.UTF_8);
        }
        boolean text = resource.mime().startsWith("text/") || resource.mime().endsWith("+xml") || resource.mime().equals("application/json");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(resource.mime() + (text ? ";charset=UTF-8" : ""))).contentLength(bytes.length)
            .header("Cache-Control", "no-store").header("Referrer-Policy", "no-referrer")
            .header("X-Content-Type-Options", "nosniff")
            // Browser PDF viewers cannot run under CSP sandbox; the fixed PDF MIME + nosniff prevents HTML execution.
            .header("Content-Security-Policy", resource.mime().equals("application/pdf") ? "default-src 'none'; base-uri 'none'; form-action 'none'"
                : "sandbox allow-scripts allow-forms; default-src 'none'; script-src 'self' 'unsafe-inline'; "
                + "style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; media-src 'self' data: blob:; "
                + "font-src 'self' data:; connect-src 'self'; form-action 'none'; base-uri 'none'; object-src 'none'")
            .header("Permissions-Policy", "camera=(), microphone=(), geolocation=(), clipboard-read=(), clipboard-write=()")
            .body(bytes);
    }
    @ExceptionHandler({IOException.class, IllegalArgumentException.class, SecurityException.class, ArtifactException.class})
    public ResponseEntity<?> failure(Exception error) {
        int status = error instanceof IOException ? 404 : error instanceof SecurityException ? 403 : 400;
        if (error instanceof ArtifactException artifact) {
            status = switch (artifact.code()) { case "artifact_not_found", "artifact_unavailable" -> 404; case "scope_mismatch" -> 403; case "artifact_too_large" -> 413; default -> 400; };
        }
        return ResponseEntity.status(status)
            .body(Map.of("error", Map.of("code", "preview_unavailable", "message", Objects.toString(error.getMessage(), "Preview is unavailable"))));
    }
}
