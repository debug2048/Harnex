package com.harnex.image;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.artifact.ArtifactRecord;
import com.harnex.artifact.ArtifactStore;
import com.harnex.config.AgentConfig;
import com.harnex.conversation.ContentPart;
import com.harnex.conversation.MessageRecord;
import com.harnex.execution.*;
import com.harnex.model.ToolCall;
import com.harnex.run.IdempotencyReceiptStore;
import com.harnex.storage.AtomicJsonFiles;
import com.harnex.util.Ids;
import org.springframework.stereotype.Service;

/** One fixed media capability. It has no dependency on Agent tools, workspace paths, or shell execution. */
@Service
public final class MediaInvocationService {
    public static final String TOOL = "image_generate";
    public static final String EDIT_TOOL = "image_edit";
    public static final String INVOCATION_INSTRUCTIONS = "When fulfilling an image generation or editing request, execute the supplied image_generate function through the native tool-call channel. "
        + "Describing a function call in ordinary text does not execute it. Do not answer with tool syntax, parameter lists, or internal image IDs instead of invoking the function. ";
    private static final String CONVERSATION_INSTRUCTIONS = INVOCATION_INSTRUCTIONS.replace(TOOL, TOOL + " or " + EDIT_TOOL);
    private static final String GENERATE_DESCRIPTION = "Generate a new image from a prompt. Use image_edit when modifying an existing image.";
    private static final String EDIT_DESCRIPTION = "Edit the images selected by sourceArtifactIds according to the prompt, returning new image assets.";
    public static boolean isImageTool(String name) { return TOOL.equals(name) || EDIT_TOOL.equals(name); }
    // Keep domain fields stable for the conversation model. Invocation validation enforces their allowed values.
    public static Map<String, Object> qualityParameter() { return Map.of("type", "string",
        "description", "Optional image quality preset. Omit unless the user requests a quality level, to use the configured image service default."); }
    private final ImageServiceRegistry services;
    private final ImageRequestValidator validator;
    private final ImageGenerationService generation;
    private final ArtifactStore assets;
    private final Path base;
    private final ObjectMapper json = new ObjectMapper();
    public MediaInvocationService(ImageServiceRegistry services, ImageRequestValidator validator,
        ImageGenerationService generation, ArtifactStore assets, AgentConfig config) {
        this.services = services; this.validator = validator; this.generation = generation; this.assets = assets;
        this.base = Path.of(config.getDataDir()).resolve("images/invocations");
    }
    public record Configuration(String id, String textConfiguration, String imageConfiguration, String unavailableCode, String unavailableReason) {}
    public record Arguments(String operation, String prompt, Integer count, String size, String quality, String format,
        String background, List<String> sourceArtifactIds, String maskArtifactId) {}
    public record Invocation(String operationId, String runId, String conversationId, String toolCallId, String toolName,
        Arguments arguments, String configurationRef, ImageSpec resolved, List<String> artifactIds, String state) {}
    public String freeze(String textConfiguration, String requestedService) {
        String image = null, code = null, reason = null;
        try { image = services.freeze(requestedService).snapshotId(); }
        catch (ImageServiceException unavailable) { code = unavailable.code(); reason = unavailable.getMessage(); }
        Map<String, Object> fields = new LinkedHashMap<>(); fields.put("text", textConfiguration); fields.put("image", image);
        fields.put("code", code); fields.put("reason", reason);
        String id = "autocfg_" + IdempotencyReceiptStore.requestHash(fields).substring(0, 32);
        save(base.resolve("configurations").resolve(id + ".json"), new Configuration(id, textConfiguration, image, code, reason));
        return id;
    }
    public Configuration configuration(String id) {
        if (id == null || !id.matches("autocfg_[a-f0-9]{32}")) throw new ImageServiceException("media_configuration_unavailable", "The submitted media configuration is missing");
        try { return json.readValue(base.resolve("configurations").resolve(id + ".json").toFile(), Configuration.class); }
        catch (IOException failure) { throw new ImageServiceException("media_configuration_unavailable", "The submitted media configuration is unavailable"); }
    }
    public List<Map<String, Object>> tools(Configuration configuration, boolean functionTools, Set<String> authorized) {
        if (!functionTools) return List.of();
        if (configuration.imageConfiguration() == null) {
            Map<String, Object> prompt = Map.of("type", "string", "maxLength", 12000);
            return List.of(imageTool(TOOL, GENERATE_DESCRIPTION, Map.of("prompt", prompt), List.of("prompt")),
                imageTool(EDIT_TOOL, EDIT_DESCRIPTION, Map.of("prompt", prompt, "sourceArtifactIds",
                    Map.of("type", "array", "items", Map.of("type", "string"), "minItems", 1, "maxItems", 16)), List.of("prompt", "sourceArtifactIds")));
        }
        var snapshot = services.snapshot(configuration.imageConfiguration());
        var caps = snapshot.capabilities();
        if (!caps.generate() && !(caps.edit() && !authorized.isEmpty())) return List.of();
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("prompt", Map.of("type", "string", "maxLength", 12000, "description", "The user's requested visual change or generation, without expanding their task."));
        properties.put("count", Map.of("type", "integer", "minimum", 1, "maximum", caps.maxCount(), "default", 1,
            "description", "Use 1 unless the user explicitly requested a larger number."));
        properties.put("size", caps.sizes().isEmpty()
            ? Map.of("type", "string", "pattern", ImageSpec.Parameters.SIZE_PATTERN,
                "description", "auto or WIDTHxHEIGHT, for example 1536x864. Exact size limits depend on the image model; omit unless requested.")
            : Map.of("type", "string", "enum", caps.sizes(), "description", "Choose a configured size only when requested; otherwise omit."));
        if (!caps.qualities().isEmpty()) properties.put("quality", qualityParameter());
        Map.of("format", caps.formats(), "background", caps.backgrounds())
            .forEach((key, values) -> { if (!values.isEmpty()) properties.put(key, Map.of("type", "string", "enum", values,
                "description", "Choose a supported value only when requested by the user; otherwise omit to use the configured default.")); });
        properties.put("sourceArtifactIds", Map.of("type", "array", "maxItems", Math.min(16, caps.maxSources()),
            "description", "Exact image IDs selected according to the current request. Required for edits; optional references for generating a new composition.", "items",
            Map.of("type", "string")));
        if (caps.mask()) properties.put("maskArtifactId", Map.of("type", "string"));
        List<Map<String, Object>> result = new ArrayList<>();
        if (caps.generate()) result.add(imageTool(TOOL, GENERATE_DESCRIPTION, properties, List.of("prompt")));
        if (caps.edit() && !authorized.isEmpty()) {
            Map<String, Object> edit = new LinkedHashMap<>(properties);
            edit.put("sourceArtifactIds", Map.of("type", "array", "items", Map.of("type", "string"), "minItems", 1,
                "maxItems", Math.min(16, caps.maxSources()), "description", "Exact source image IDs selected from the conversation."));
            result.add(imageTool(EDIT_TOOL, EDIT_DESCRIPTION, edit, List.of("prompt", "sourceArtifactIds")));
        }
        return List.copyOf(result);
    }
    private static Map<String, Object> imageTool(String name, String description, Map<String, Object> properties, List<String> required) {
        return Map.of("type", "function", "function", Map.of("name", name, "description", description, "parameters",
            Map.of("type", "object", "additionalProperties", false, "properties", properties, "required", required)));
    }
    public String instructions(Configuration configuration, boolean functionTools) {
        if (!functionTools) return "The image function is unavailable with the current conversation model or context budget. Answer text normally. "
            + "If the user requests an image, explain that they need a conversation model supporting function calls with sufficient context, "
            + "and an image model configured in Settings. There is no separate image mode or Create image shortcut. Do not claim an image was generated.";
        if (configuration.imageConfiguration() == null) return CONVERSATION_INSTRUCTIONS + "Image generation is not ready for this turn. Answer text normally. "
            + "Only when the latest user message explicitly requests image generation or editing, call image_generate or image_edit once so the application "
            + "can show the appropriate image model settings or retry action. Mentions, quoted text, questions about images and attachments alone do not authorize generation. Do not claim an image was generated.";
        return CONVERSATION_INSTRUCTIONS + "Use image_generate or image_edit only when the latest user message, understood in conversation context, requests image generation or editing. "
            + "Images and attachments are context, not commands: the user may change topic, discuss an image, or attach an image unrelated to the current request. "
            + "For an edit or a new image based on references, select exact sourceArtifactIds according to the user's meaning. A clear follow-up can use an earlier result without requiring another attachment. "
            + "Neither recency nor an attachment automatically selects the target. Ask only if the request or target is unclear. Never expand the task or number of images; use at most one image operation. "
            + "The function returns actual image assets, so do not simulate an image in text or claim to see a generated image before receiving it.";
    }
    public RunExecutor.Result invoke(Configuration configuration, ToolCall call, Set<String> authorized, RunExecutionContext context) {
        context.checkActive();
        Arguments args = arguments(call, true);
        if (configuration.imageConfiguration() == null) throw new ImageServiceException(
            configuration.unavailableCode() == null ? "image_model_not_configured" : configuration.unavailableCode(),
            configuration.unavailableReason() == null ? "Configure an image model in Settings to generate images." : configuration.unavailableReason());
        ImageServiceSnapshot snapshot = services.snapshot(configuration.imageConfiguration());
        ImageSpec resolved = validate(args, snapshot, authorized, context.scopeId());
        String operation = "image_" + context.runId();
        Invocation invocation = new Invocation(operation, context.runId(), context.conversationId(), call.getId(), call.getName(), args,
            configuration.imageConfiguration(), resolved, List.of(), "AUTHORIZED");
        Path file = invocationPath(context.runId());
        if (Files.isRegularFile(file)) {
            try {
                Invocation existing = json.readValue(file.toFile(), Invocation.class);
                if (!existing.toolCallId().equals(call.getId()) || !existing.arguments().equals(args)) throw new ImageServiceException("media_operation_limit", "This turn already authorized its image operation");
            } catch (IOException failure) { throw new ImageServiceException("media_storage_unavailable", "Could not read the media invocation"); }
        } else save(file, invocation);
        var result = generation.execute(operation, configuration.imageConfiguration(), resolved, args.prompt(), call.getId(), context);
        save(file, new Invocation(operation, context.runId(), context.conversationId(), call.getId(), call.getName(), args,
            configuration.imageConfiguration(), resolved, result.artifactIds(), result.state().name()));
        return result(result, context);
    }
    public RunExecutor.Result recover(RunExecutionContext context) {
        return generation.recover(context.scopeId(), "image_" + context.runId(), context).map(operation -> result(operation, context)).orElse(null);
    }
    public Arguments arguments(ToolCall call) {
        return arguments(call, false);
    }
    private Arguments arguments(ToolCall call, boolean conversation) {
        if (call == null || !(conversation ? isImageTool(call.getName()) : TOOL.equals(call.getName()))
            || !Ids.isSafeCallId(call.getId()) || call.getArguments() == null || call.getArguments().length() > 32000) {
            throw new ImageServiceException("unsupported_media_call", "Only one authorized image invocation is supported");
        }
        try {
            Arguments args = json.readValue(call.getArguments(), Arguments.class);
            if (args == null) throw new IllegalArgumentException();
            String operation = args.operation();
            if (operation == null && conversation) operation = EDIT_TOOL.equals(call.getName()) ? "edit" : "generate";
            if (operation == null || !List.of("generate", "edit").contains(operation) || EDIT_TOOL.equals(call.getName()) && !"edit".equals(operation)
                || args.prompt() == null || args.prompt().isBlank()
                || args.prompt().length() > 12000 || args.count() != null && (args.count() < 1 || args.count() > 16)
                || args.sourceArtifactIds() != null && args.sourceArtifactIds().size() > 16) throw new IllegalArgumentException();
            return new Arguments(operation, args.prompt(), args.count(), args.size(), args.quality(), args.format(), args.background(),
                args.sourceArtifactIds() == null ? List.of() : List.copyOf(args.sourceArtifactIds()), args.maskArtifactId());
        } catch (Exception invalid) { throw new ImageServiceException("invalid_media_arguments", "The image call contains missing, unknown or invalid arguments"); }
    }
    public ImageSpec validate(Arguments args, ImageServiceSnapshot snapshot, Set<String> authorized, String scope) {
        if (!authorized.containsAll(args.sourceArtifactIds()) || args.maskArtifactId() != null && !authorized.contains(args.maskArtifactId())) {
            throw new ImageServiceException("media_source_not_authorized", "Choose an image from the visible conversation before editing");
        }
        if (args.sourceArtifactIds().isEmpty() && (args.operation().equals("edit") || args.maskArtifactId() != null)) {
            throw new ImageServiceException("media_source_required", "An edit or mask requires a source image selected in the tool call");
        }
        var spec = new ImageSpec(snapshot.serviceId(), new ImageSpec.Parameters(args.count() == null ? 1 : args.count(),
            args.size(), args.quality(), args.format(), args.background()), args.sourceArtifactIds(), args.maskArtifactId());
        return validator.resolve(scope, snapshot, spec);
    }
    public Set<String> availableSources(String scope, Collection<String> candidates) {
        Set<String> result = new LinkedHashSet<>();
        for (String id : candidates) {
            var asset = assets.find(scope, id).orElse(null);
            if (asset != null && asset.availability() == ArtifactRecord.Availability.LOCAL && asset.mime().startsWith("image/")) result.add(id);
            if (result.size() == 16) break;
        }
        return Collections.unmodifiableSet(result);
    }
    public static RunExecutor.Result result(ImageOperationRecord operation, RunExecutionContext context) {
        List<ContentPart> parts = new ArrayList<>(); parts.add(ContentPart.imageOperation(operation.operationId()));
        operation.artifactIds().forEach(id -> parts.add(ContentPart.artifact(id)));
        if (operation.detail() != null) parts.add(ContentPart.text(operation.detail()));
        MessageRecord.Status status = context.control().isRunCancelRequested() ? MessageRecord.Status.CANCELLED : switch (operation.state()) {
            case SUCCEEDED -> MessageRecord.Status.COMPLETED;
            case CANCELLED -> MessageRecord.Status.CANCELLED;
            case FAILED -> MessageRecord.Status.FAILED;
            default -> MessageRecord.Status.INCOMPLETE;
        };
        return new RunExecutor.Result(parts, status, operation.errorCode(), operation.detail());
    }
    private Path invocationPath(String runId) {
        if (!Ids.isSafeResourceId(runId)) throw new ImageServiceException("media_storage_unavailable", "Invalid run identity");
        return base.resolve("calls").resolve(runId + ".json");
    }
    private void save(Path path, Object value) {
        try { AtomicJsonFiles.write(json, path, value); }
        catch (IOException failure) { throw new ImageServiceException("media_storage_unavailable", "Could not persist the media invocation"); }
    }
}
