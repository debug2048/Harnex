package com.harnex.tool.builtin;
import java.util.*;
import org.springframework.stereotype.Component;
import com.harnex.config.ProjectRuntime;
import com.harnex.image.AgentImageService;
import com.harnex.tool.*;
@Component @ProjectRuntime
public final class ImageInspectTool implements Tool {
    private final AgentImageService images;
    public ImageInspectTool(AgentImageService images) { this.images = images; }
    public String getName() { return "image_inspect"; }
    public String getDescription() { return "Attach 1–4 uploaded/generated image assets as actual image input to the next model call. Use artifactIds returned by image_generate or attachment metadata, never local file names/paths. Requires image input support."; }
    public Set<ToolCapability> capabilities() { return Set.of(ToolCapability.READS_FILESYSTEM,ToolCapability.PLAN_MODE_SAFE); }
    public ToolDefinition getDefinition() { return ToolDefinition.builder().name(getName()).description(getDescription()).parameters(Map.of("type","object","additionalProperties",false,
        "properties",Map.of("artifactIds",Map.of("type","array","items",Map.of("type","string","minLength",1),"minItems",1,"maxItems",4)),"required",List.of("artifactIds"))).build(); }
    public ToolResult execute(Map<String,Object> input,ToolContext context) { return images.inspect(input,context); }
}
