package com.harnex.tool.builtin;
import java.util.*;
import org.springframework.stereotype.Component;
import com.harnex.config.ProjectRuntime;
import com.harnex.image.AgentImageService;
import com.harnex.tool.*;
@Component @ProjectRuntime
public final class ImageExportTool implements Tool {
    private final AgentImageService images;
    public ImageExportTool(AgentImageService images) { this.images = images; }
    public String getName() { return "image_export"; }
    public String getDescription() { return "Save an image asset to a project-relative path within existing write permissions. Existing files require their exact expectedChecksum before replacement."; }
    public Set<ToolCapability> capabilities() { return Set.of(ToolCapability.WRITES_FILESYSTEM,ToolCapability.DESTRUCTIVE_IRREVERSIBLE); }
    public ToolDefinition getDefinition() { return ToolDefinition.builder().name(getName()).description(getDescription()).parameters(Map.of("type","object","additionalProperties",false,
        "properties",Map.of("artifactId",Map.of("type","string"),"path",Map.of("type","string"),"expectedChecksum",Map.of("type","string")),"required",List.of("artifactId","path"))).build(); }
    public ToolResult execute(Map<String,Object> input,ToolContext context) { return images.export(input,context); }
}
