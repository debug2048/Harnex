package com.harnex.mcp;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import com.harnex.artifact.ArtifactException;
import com.harnex.artifact.ArtifactRecord;
import com.harnex.artifact.ArtifactStore;
import com.harnex.execution.ScopeIdentity;
import com.harnex.safety.SecretRedactor;
import com.harnex.tool.ToolContext;
import com.harnex.tool.ToolResult;

/** Converts standard MCP content into ordinary scope-owned assets. Private _meta is never copied to the model/UI. */
@Service
public final class McpResultAssets {
    private final ArtifactStore assets;
    private final ScopeIdentity scope;
    public McpResultAssets(ArtifactStore assets, ScopeIdentity scope) { this.assets = assets; this.scope = scope; }
    public ToolResult convert(McpClient.McpToolResult result, ToolContext context) {
        List<String> references = new ArrayList<>();
        if (result.getStructuredContent() != null) {
            String json = SecretRedactor.redact(result.getStructuredContent().toPrettyString());
            save(references, "result.json", "application/json", json.getBytes(StandardCharsets.UTF_8), false, context);
        }
        if (result.getContent() != null && result.getContent().isArray()) {
            for (var part : result.getContent()) {
                if (references.size() >= 8) break;
                try {
                    if ("image".equals(part.path("type").asText())) {
                        String data = part.path("data").asText(), mime = part.path("mimeType").asText();
                        if (data.length() > 16 * 1024 * 1024) continue;
                        String extension = switch (mime) { case "image/png" -> "png"; case "image/jpeg" -> "jpg";
                            case "image/webp" -> "webp"; case "image/gif" -> "gif"; default -> null; };
                        if (extension != null) save(references, "result." + extension, mime, Base64.getDecoder().decode(data), true, context);
                    } else if ("resource".equals(part.path("type").asText()) && part.path("resource").path("text").isTextual()) {
                        var resource = part.path("resource");
                        String mime = resource.path("mimeType").asText("text/plain");
                        String extension = switch (mime) { case "text/html" -> "html"; case "text/markdown" -> "md";
                            case "application/json" -> "json"; case "text/csv" -> "csv"; default -> "txt"; };
                        String text = SecretRedactor.redact(resource.path("text").asText());
                        save(references, "result." + extension, mime, text.getBytes(StandardCharsets.UTF_8), false, context);
                    }
                } catch (IllegalArgumentException ignored) { /* Malformed images retain the text placeholder. */ }
            }
        }
        String prefix = references.stream().map(id -> "[artifact:" + id + "]").collect(Collectors.joining("\n"));
        return ToolResult.builder().error(result.isError()).content(prefix.isEmpty() ? result.getText() : prefix + "\n" + result.getText()).build();
    }
    private void save(List<String> ids, String name, String mime, byte[] bytes, boolean image, ToolContext context) {
        if (bytes.length == 0 || bytes.length > 12 * 1024 * 1024) return;
        try {
            ArtifactRecord record = assets.save(scope.scopeId(), new ArtifactStore.SaveRequest(null, name, mime,
                new ArtifactRecord.Creator("MCP", context.getRunId(), null), List.of(), image), new ByteArrayInputStream(bytes));
            ids.add(record.artifactId());
        } catch (ArtifactException ignored) { /* A failed preview must not repeat a completed external tool. */ }
    }
}
