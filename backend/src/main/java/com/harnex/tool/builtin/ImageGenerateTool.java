package com.harnex.tool.builtin;
import java.util.*;
import org.springframework.stereotype.Component;
import com.harnex.config.ProjectRuntime;
import com.harnex.image.AgentImageService;
import com.harnex.image.MediaInvocationService;
import com.harnex.execution.ImageSpec;
import com.harnex.tool.*;
@Component @ProjectRuntime
public final class ImageGenerateTool implements Tool {
    private final AgentImageService images;
    public ImageGenerateTool(AgentImageService images) { this.images = images; }
    public String getName() { return "image_generate"; }
    public String getDescription() { return MediaInvocationService.INVOCATION_INSTRUCTIONS
        + "Generate or edit images when requested by the current user message interpreted in conversation context, using a configured image service. "
        + "An attachment or earlier image alone does not request an operation or select its source. "
        + "For edits or new compositions using references choose exact sourceArtifactIds from message image references or prior image tool results according to the user's request. "
        + "A clear follow-up does not require another attachment. Ask only when the user's intent or target is unclear. "
        + "Returns asset IDs; does not inspect images or write project files."; }
    public Set<ToolCapability> capabilities() { return Set.of(ToolCapability.WRITES_NETWORK,ToolCapability.MUTATES_THREAD_STATE); }
    public boolean available() { return images.generationConfigured(); }
    public int timeoutMillis() { return 1_800_000; }
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder().name(getName()).description(getDescription()).parameters(Map.of("type","object","additionalProperties",false,
            "properties",Map.of("serviceId",Map.of("type","string"),"operation",Map.of("type","string","enum",List.of("generate","edit")),
                "prompt",Map.of("type","string","maxLength",12000),"count",Map.of("type","integer","minimum",1,"maximum",10,"default",1),
                "size",Map.of("type","string","pattern",ImageSpec.Parameters.SIZE_PATTERN),
                "quality",Map.of("type","string","enum",List.of("auto","low","medium","high","xhigh","max")),
                "format",Map.of("type","string","enum",List.of("png","jpeg","webp")),
                "background",Map.of("type","string","enum",List.of("auto","opaque","transparent")),
                "sourceArtifactIds",Map.of("type","array","items",Map.of("type","string"),"maxItems",16,
                    "description","Required for edits; optional for a new composition based on reference images. Choose IDs according to the current request."),"maskArtifactId",Map.of("type","string")),
            "required",List.of("operation","prompt"))).build();
    }
    public ToolResult execute(Map<String,Object> input,ToolContext context) { return images.generate(input,context); }
}
