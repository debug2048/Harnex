package com.harnex.image;

import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.artifact.*;
import com.harnex.config.*;
import com.harnex.core.AgentCtx;
import com.harnex.execution.*;
import com.harnex.model.*;
import com.harnex.provider.*;
import com.harnex.run.IdempotencyReceiptStore;
import com.harnex.run.RunControlSignal;
import com.harnex.tool.*;
import com.harnex.util.Ids;
import org.springframework.stereotype.Service;

/** Agent adapter for the same image operation and asset services used by ordinary conversation. */
@Service @ProjectRuntime
public final class AgentImageService {
    private final AgentConfig config;
    private final ScopeIdentity scope;
    private final ImageServiceRegistry services;
    private final ImageGenerationService generation;
    private final MediaInvocationService media;
    private final ArtifactAttachments attachments;
    private final ResolvedProviderRegistry providers;
    private final ProjectArtifactExporter exports;
    private final ObjectMapper json = new ObjectMapper();
    public AgentImageService(AgentConfig config, ScopeIdentity scope, ImageServiceRegistry services,
        ImageGenerationService generation, MediaInvocationService media, ArtifactAttachments attachments,
        ResolvedProviderRegistry providers, ProjectArtifactExporter exports) {
        this.config = config; this.scope = scope; this.services = services; this.generation = generation;
        this.media = media; this.attachments = attachments; this.providers = providers; this.exports = exports;
    }
    /** Static configuration only; transient health never changes the Agent's tool table. */
    public boolean generationConfigured() {
        return config.getImageServices() != null && config.getImageServices().values().stream().anyMatch(service -> service != null && service.isEnabled()
            && service.getModel() != null && !service.getModel().isBlank()
            && ("openai-images".equals(service.getProfile()) || "openai-responses-image".equals(service.getProfile())));
    }
    public List<ChatMessage> withImageReferences(List<ChatMessage> messages) {
        return messages.stream().map(attachments::withImageReferences).toList();
    }
    /** Recomputed from the actual target on every attempt, including fallback and delegated model calls. */
    public List<Map<String, Object>> inspectionToolsForTarget(List<Map<String, Object>> tools, String provider, String model) {
        if (supportsVision(provider, model)) return tools;
        return tools.stream().filter(tool -> !(tool.get("function") instanceof Map<?, ?> function
            && "image_inspect".equals(function.get("name")))).toList();
    }
    public ToolResult generate(Map<String, Object> input, ToolContext owner) {
        if (owner.getAgentContext() == null || owner.getRunId() == null || owner.getToolCallId() == null) return ToolResult.failure("Image generation requires an active Agent tool call");
        try {
            Map<String, Object> arguments = new LinkedHashMap<>(input); Object selected = arguments.remove("serviceId");
            if (selected != null && !(selected instanceof String)) return ToolResult.failure("serviceId must be a string");
            var args = media.arguments(new ToolCall(owner.getToolCallId(), MediaInvocationService.TOOL, json.writeValueAsString(arguments)));
            var snapshot = services.freeze((String)selected);
            Set<String> candidates = new LinkedHashSet<>(args.sourceArtifactIds()); if (args.maskArtifactId() != null) candidates.add(args.maskArtifactId());
            var spec = media.validate(args, snapshot, media.availableSources(scope.scopeId(), candidates), scope.scopeId());
            AgentCtx agent = owner.getAgentContext(); var cancel = new RunControlSignal();
            long remaining = agent.getRuntimeBudget() == null ? 1_800_000 : Math.min(1_800_000, agent.getRuntimeBudget().remainingMs());
            String conversation = owner.getRootThreadId() == null ? owner.getThread().getId() : owner.getRootThreadId();
            var callOwner = ProviderCallScope.current();
            String step = callOwner != null && callOwner.stepId() != null ? callOwner.stepId() : owner.getRunId();
            var context = new RunExecutionContext(scope.scopeId(), conversation, step,
                owner.getRunId(), cancel, Instant.now().plusMillis(Math.max(0, remaining)), new ExecutionBudget(1,1), owner.getEmit() == null ? ignored -> {} : owner.getEmit());
            String id = "image_" + IdempotencyReceiptStore.requestHash(Map.of("run", owner.getRunId(), "call", owner.getToolCallId())).substring(0,32);
            try (var hook = agent.registerCancellation(cancel::requestRunCancel);
                 var root = agent.getControlSignal().onRunCancel(cancel::requestRunCancel)) {
                if (agent.isCancelled()) cancel.requestRunCancel();
                var operation = generation.execute(id, snapshot.snapshotId(), spec, args.prompt(), owner.getToolCallId(), context);
                Map<String,Object> result = new LinkedHashMap<>(); result.put("operationId",id); result.put("state",operation.state()); result.put("artifactIds",operation.artifactIds());
                if (operation.errorCode() != null) result.put("errorCode",operation.errorCode());
                result.put("visualInspection", supportsVision(owner.getAgentContext(), owner.getThread())
                    ? "Not inspected. Use image_inspect with the returned artifactIds before describing actual image contents."
                    : "Not inspected. The current model cannot receive image input. A model with image input support is required for visual verification; file_read and pixel statistics do not substitute for seeing the image.");
                return ToolResult.builder().content(json.writeValueAsString(result)).error(operation.state() != ImageOperationRecord.State.SUCCEEDED)
                    .metadata(Map.of("imageOperationId",id,"artifactIds",operation.artifactIds())).build();
            }
        } catch (Exception failure) { return ToolResult.failure(failure instanceof ImageServiceException image ? image.code() + ": " + image.getMessage() : failure.getMessage()); }
    }
    public ToolResult inspect(Map<String,Object> input, ToolContext owner) {
        if (!input.keySet().equals(Set.of("artifactIds")) || !(input.get("artifactIds") instanceof List<?> ids)
            || ids.isEmpty() || ids.size() > 4 || ids.stream().anyMatch(id -> !(id instanceof String value) || value.isBlank())) return ToolResult.failure("Provide 1–4 non-empty artifactIds from uploaded or generated image assets. File paths are not artifactIds. Do not send an empty list.");
        if (ids.stream().anyMatch(id -> !Ids.isSafeResourceId((String)id))) {
            return ToolResult.failure("artifactIds must be asset IDs from uploaded or generated images, not file names or paths. Attach/import a local image as an asset first.");
        }
        if (!supportsVision(owner.getAgentContext(), owner.getThread())) return ToolResult.failure("The current model cannot inspect images. Do not describe their appearance; choose a model with image input support.");
        var requested = ids.stream().map(String.class::cast).toList();
        if (!media.availableSources(scope.scopeId(), requested).containsAll(requested)) return ToolResult.failure("An image is unavailable in this scope");
        try {
            attachments.resolve(owner.getThread().getId(), requested);
            return ToolResult.builder().content("The requested images will be attached after this tool batch. Evaluate only the actual supplied image blocks.")
                .metadata(Map.of("visualArtifactIds",requested)).build();
        } catch (ArtifactException failure) { return ToolResult.failure(failure.getMessage()); }
    }
    /** All assistant tool calls are paired before adding the actual multimodal input for the next model call. */
    public void appendInspections(AgentCtx agent, List<ToolResult> results) {
        Set<String> ids = new LinkedHashSet<>();
        for (ToolResult result : results) {
            if (result.isError() || !"image_inspect".equals(result.getToolName()) || result.getMetadata() == null) continue;
            if (result.getMetadata().get("visualArtifactIds") instanceof List<?> values) for (Object id : values) if (id instanceof String value && ids.size() < 4) ids.add(value);
        }
        if (ids.isEmpty()) return;
        if (!supportsVision(agent, agent.getThread())) {
            agent.getThread().getMessages().add(ChatMessage.user("Image inspection was unavailable. No visual input was supplied; do not claim to have seen these images.")); return;
        }
        try {
            ChatMessage message = ChatMessage.user("Actual images requested by image_inspect. Describe their appearance only if the image blocks are supplied to you.");
            message.setChannel("harnex-image-inspection");
            message.setAttachments(attachments.resolve(agent.getThread().getId(), List.copyOf(ids)));
            agent.getThread().getMessages().add(message);
        } catch (ArtifactException failure) {
            agent.getThread().getMessages().add(ChatMessage.user("Image inspection could not load the image bytes. Do not claim to have inspected the images."));
        }
    }
    private boolean supportsVision(AgentCtx agent, ConversationThread thread) {
        if (!config.getVision().isEnabled() || thread == null) return false;
        String provider = agent != null && agent.getLastProvider() != null ? agent.getLastProvider() : thread.getProvider();
        String model = agent != null && agent.getLastModel() != null ? agent.getLastModel() : thread.getModel();
        return supportsVision(provider, model);
    }
    private boolean supportsVision(String provider, String model) {
        if (!config.getVision().isEnabled() || provider == null || model == null) return false;
        return providers.find(provider).map(p -> ProviderModelCapabilities.from(config,p).forModel(model).supportsModality("image")).orElse(false);
    }
    public ToolResult export(Map<String,Object> input, ToolContext owner) {
        if (owner.getRunId() == null || owner.getToolCallId() == null) return ToolResult.failure("Export requires an active Agent tool call");
        if (!Set.of("artifactId","path","expectedChecksum").containsAll(input.keySet()) || !(input.get("artifactId") instanceof String asset)
            || !(input.get("path") instanceof String path) || input.get("expectedChecksum") != null && !(input.get("expectedChecksum") instanceof String)) return ToolResult.failure("Provide artifactId, path and optional expectedChecksum only");
        try {
            String id = "agentexport_" + IdempotencyReceiptStore.requestHash(Map.of("run",owner.getRunId(),"call",owner.getToolCallId())).substring(0,32);
            return ToolResult.ok(json.writeValueAsString(exports.export(asset,new ProjectArtifactExporter.Request(id,path,(String)input.get("expectedChecksum")))));
        } catch (ProjectArtifactExporter.Failure failure) {
            return ToolResult.failure(failure.code() + ": " + failure.getMessage() + (failure.existingChecksum() == null ? "" : "; existingChecksum=" + failure.existingChecksum()));
        } catch (Exception failure) { return ToolResult.failure(failure); }
    }
}
