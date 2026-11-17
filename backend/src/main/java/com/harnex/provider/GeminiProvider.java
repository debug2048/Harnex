package com.harnex.provider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.harnex.observability.HarnexMetrics;
import com.harnex.model.ChatMessage;
import com.harnex.model.MessageAttachment;
import com.harnex.model.StreamEvent;
import com.harnex.model.ToolCall;
import com.harnex.net.ProxyResolution;

import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * Native Google Gemini Interactions API adapter.
 *
 * <p>The Interactions wire format is deliberately not translated through OpenAI Chat: thoughts, function calls,
 * server-side tools and model output are ordered first-class {@code steps}. Harnex uses stateless mode
 * ({@code store:false}) and persists every returned step verbatim. This is required for replaying Gemini thought
 * signatures and the distinct signatures carried by built-in tool call/result steps.</p>
 */
@Slf4j
public final class GeminiProvider extends AbstractHttpProvider {

    private static final int MAX_NATIVE_STEPS = 256;
    private static final int MAX_NATIVE_STEPS_BYTES = 8 * 1024 * 1024;
    private static final long MAX_INLINE_REQUEST_BYTES = 20L * 1024 * 1024;
    private static final Set<String> IMAGE_MIMES = Set.of("image/png", "image/jpeg", "image/webp", "image/heic",
        "image/heif", "image/gif", "image/bmp", "image/tiff");
    private static final Set<String> DOCUMENT_MIMES = Set.of("application/pdf", "text/csv");
    private static final Set<String> AUDIO_MIMES =
        Set.of("audio/wav", "audio/mp3", "audio/mpeg", "audio/aiff", "audio/aac", "audio/ogg", "audio/flac",
            "audio/m4a", "audio/l16", "audio/opus", "audio/alaw", "audio/mulaw");
    private static final Set<String> VIDEO_MIMES = Set.of("video/mp4", "video/mpeg", "video/mov",
        "video/avi", "video/x-flv", "video/mpg", "video/webm", "video/wmv", "video/3gpp");

    private final Options opts;
    private final ProviderModelCapabilities modelCapabilities;
    private final boolean attachmentsEnabled;
    private final Path uploadsRoot;
    private final long maxAttachmentBytes;
    private final int maxAttachmentsPerRequest;

    /** Request preferences; model/endpoint support remains capability-gated. */
    public record Options(int defaultMaxTokens, String effort, boolean nativeWebSearch) {
        public static Options defaults() {
            return new Options(0, null, false);
        }
    }

    public GeminiProvider(String name, String baseUrl, String apiKey, String defaultModel, HarnexMetrics metrics) {
        this(name, baseUrl, apiKey, defaultModel, false, 0L, 0, null, metrics, null, Options.defaults(),
            ProviderModelCapabilities.conservative("gemini"));
    }

    /** Full constructor used by {@link ProviderRouter}; capabilities are frozen once at startup. */
    public GeminiProvider(String name, String baseUrl, String apiKey, String defaultModel,
        boolean attachmentsEnabled, long maxAttachmentBytes, int maxAttachmentsPerRequest, Path uploadsRoot,
        HarnexMetrics metrics, ProxyResolution.Resolved proxy, Options opts,
        ProviderModelCapabilities modelCapabilities) {
        super(name, baseUrl == null || baseUrl.isBlank() ? "https://generativelanguage.googleapis.com" : baseUrl,
            apiKey, defaultModel, metrics, proxy);
        this.opts = opts == null ? Options.defaults() : opts;
        this.modelCapabilities = modelCapabilities == null
            ? ProviderModelCapabilities.conservative("gemini") : modelCapabilities;
        this.attachmentsEnabled = attachmentsEnabled;
        this.maxAttachmentBytes = Math.max(0L, maxAttachmentBytes);
        this.maxAttachmentsPerRequest = Math.max(0, maxAttachmentsPerRequest);
        this.uploadsRoot = uploadsRoot == null ? null : uploadsRoot.toAbsolutePath().normalize();
    }

    @Override
    public void streamChat(ChatRequest request, Consumer<StreamEvent> onEvent) {
        streamChat(request, onEvent, 0L);
    }

    @Override
    public void streamChat(ChatRequest request, Consumer<StreamEvent> onEvent, long ttftBudgetMs) {
        String model = effectiveModel(request.getModel());
        ProviderModelCapabilities.Profile profile = requireGeminiProfile(model);
        if (!profile.streaming()) {
            throw new ProviderException("config", model + " does not declare Gemini streaming support");
        }
        Map<String, Object> body = buildBody(request, true);
        Request httpRequest = buildRequest(body);

        String requestId = ProviderStreamLogger.newRequestId();
        long startedAt = System.nanoTime();
        if (log.isDebugEnabled()) {
            ProviderStreamLogger.logRequest(log, requestId, name, httpRequest.url().toString(), json(body));
        }

        StreamState state = new StreamState();
        AtomicBoolean firstContentSeen = new AtomicBoolean(false);
        AtomicBoolean messageStarted = new AtomicBoolean(false);
        AtomicBoolean terminalSeen = new AtomicBoolean(false);
        Call call = newCall(httpRequest);
        long ttftMs = ttftBudgetMs > 0 ? Math.min(ttftBudgetMs, STREAM_TTFT_MS) : STREAM_TTFT_MS;
        ProviderException pending = readSseStream(call, ttftMs, firstContentSeen, "network", () -> {
            onEvent.accept(StreamEvent.of("message_start", Map.of("provider", name, "model", model)));
            messageStarted.set(true);
        }, payload -> {
            JsonNode event = M.readTree(payload);
            String eventType = event.path("event_type").asText("");
            if ("error".equals(eventType)) {
                terminalSeen.set(true);
                throw streamError(event);
            }
            processEvent(event, state, firstContentSeen, onEvent);
            if ("interaction.completed".equals(eventType)) {
                terminalSeen.set(true);
            }
            return terminalSeen.get();
        });

        if (pending == null && !terminalSeen.get()) {
            pending = new ProviderException("network", "Gemini stream ended before interaction.completed/error");
        }
        if (pending == null && !isSuccessfulStatus(state.status)) {
            pending = new ProviderException("incomplete",
                "Gemini interaction ended with status=" + (state.status == null ? "unknown" : state.status));
        }
        try {
            state.finishFunctionArguments();
        } catch (ProviderException invalidCall) {
            if (pending == null) {
                pending = invalidCall;
            }
        }

        if (state.usage != null) {
            Map<String, Object> usage = new LinkedHashMap<>(state.usage.eventData());
            usage.put("model", model);
            usage.put("provider", name);
            onEvent.accept(StreamEvent.of("usage", usage));
            state.usage.record(metrics);
        }

        List<ToolCall> calls = List.of();
        String nativeSteps = null;
        if (pending == null) {
            try {
                calls = state.toolCalls();
                if (!state.steps.isEmpty()) {
                    nativeSteps = nativeStepsJson(state.steps);
                }
            } catch (ProviderException invalidOutput) {
                pending = invalidOutput;
                calls = List.of();
            }
        }
        if (messageStarted.get()) {
            Map<String, Object> end = new LinkedHashMap<>();
            end.put("content", state.text.toString());
            end.put("toolCalls", calls);
            if (!state.reasoning.isEmpty()) {
                end.put("reasoning", state.reasoning.toString());
                if (pending == null) {
                    end.put("reasoningProvider", responseProviderId());
                }
            }
            if (pending == null && nativeSteps != null) {
                end.put("responseOutputProvider", responseProviderId());
                end.put("responseOutputItems", nativeSteps);
            }
            if (state.status != null) {
                end.put("stopReason", state.status);
            }
            if (pending != null) {
                end.put("interrupted", true);
                end.put("error", pending.code);
            }
            onEvent.accept(StreamEvent.of("message_end", end));
        }
        if (log.isDebugEnabled()) {
            ProviderStreamLogger.logResponse(log, requestId, name, state.text.toString(),
                state.reasoning.toString(), calls, pending == null ? state.status : pending.code,
                pending != null, (System.nanoTime() - startedAt) / 1_000_000L);
        }
        if (pending != null) {
            throw withExchange(pending, call);
        }
    }

    @Override
    public OneShotResult oneShot(String model, List<ChatMessage> messages, Float temperature) {
        return oneShot(model, messages, temperature, 0L);
    }

    @Override
    public OneShotResult oneShot(String model, List<ChatMessage> messages, Float temperature, long timeoutMs) {
        return oneShot(model, messages, temperature, timeoutMs, null);
    }

    @Override
    public OneShotResult oneShot(String model, List<ChatMessage> messages, Float temperature, long timeoutMs,
        Integer maxOutputTokens) {
        ChatRequest request = ChatRequest.builder().model(model).messages(messages).temperature(temperature)
            .maxTokens(maxOutputTokens).build();
        return oneShot(request, timeoutMs);
    }

    @Override public OneShotResult oneShot(ChatRequest request, long timeoutMs) {
        String model = request.getModel();
        Map<String, Object> body = buildBody(request, false);
        Call call = newCall(buildRequest(body));
        return runWithTimeout(call, timeoutMs, response -> {
            JsonNode interaction = M.readTree(readBoundedSuccessBody(response));
            JsonNode usage = interaction.path("usage");
            ProviderTokenUsage tokenUsage = usage.isObject() && !usage.isEmpty()
                ? ProviderUsage.gemini(usage) : null;
            if (tokenUsage != null) tokenUsage.record(metrics);
            String status = interaction.path("status").asText("");
            if (!isSuccessfulStatus(status)) {
                throw new ProviderException("incomplete",
                    "Gemini interaction ended with status=" + (status.isBlank() ? "unknown" : status)).withUsage(tokenUsage);
            }
            return new OneShotResult(outputText(interaction.path("steps")), name,
                effectiveModel(model), tokenUsage);
        });
    }

    private Map<String, Object> buildBody(ChatRequest request, boolean stream) {
        String model = effectiveModel(request.getModel());
        ProviderModelCapabilities.Profile profile = requireGeminiProfile(model);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);

        StringBuilder system = new StringBuilder();
        List<ChatMessage> history = new ArrayList<>();
        boolean leadingSystem = true;
        for (ChatMessage message : safeMessages(request.getMessages())) {
            if (leadingSystem && message != null && "system".equals(message.getRole())) {
                if (!system.isEmpty()) {
                    system.append("\n\n");
                }
                if (message.getContent() != null) {
                    system.append(message.getContent());
                }
            } else {
                leadingSystem = false;
                if (message != null) {
                    history.add(message);
                }
            }
        }
        if (!system.isEmpty()) {
            body.put("system_instruction", system.toString());
        }
        body.put("input", toInteractionInput(history, model, profile));
        body.put("store", false);
        body.put("stream", stream);

        List<Map<String, Object>> tools = toGeminiTools(request.getTools());
        boolean nativeSearch =
            ProviderCapabilityRequest.nativeWebSearchType(profile, opts.nativeWebSearch(), model, request) != null;
        if (nativeSearch) {
            tools.removeIf(GeminiProvider::isLocalWebSearch);
            tools.add(Map.of("type", "google_search", "search_types", List.of("web_search")));
        }
        boolean hasFunctions = tools.stream().anyMatch(tool -> "function".equals(tool.get("type")));
        ProviderCapabilityRequest.requireFunctionTools(profile, hasFunctions, model);
        if (!tools.isEmpty()) {
            body.put("tools", tools);
        }

        Map<String, Object> generation = new LinkedHashMap<>();
        ProviderCapabilityRequest.applyOutputTokenLimit(generation, profile, request.getMaxTokens(), opts.defaultMaxTokens());
        String effort = ProviderCapabilityRequest.reasoningEffort(profile,
            request.getReasoningEffort() != null ? request.getReasoningEffort() : opts.effort(), hasFunctions, model,
            "generation_config.thinking_level");
        if (effort != null) {
            generation.put("thinking_level", effort);
        }
        if (profile.reasoningSupported()) {
            generation.put("thinking_summaries", "auto");
        }
        if (!generation.isEmpty()) body.put("generation_config", generation);
        ProviderCapabilityRequest.applyRequestOptions(body, profile);
        return body;
    }

    private List<Map<String, Object>> toInteractionInput(List<ChatMessage> messages, String model,
        ProviderModelCapabilities.Profile profile) {
        List<Map<String, Object>> input = new ArrayList<>();
        InlineBudget budget = new InlineBudget(MAX_INLINE_REQUEST_BYTES, maxAttachmentsPerRequest);
        Map<ChatMessage, List<Map<String, Object>>> prepared = new IdentityHashMap<>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if ("user".equals(message.getRole()) && message.getAttachments() != null
                && !message.getAttachments().isEmpty()) {
                prepared.put(message, userContent(message, profile, budget));
            }
        }
        for (ChatMessage message : messages) {
            if (message.isReasoningBlock()) {
                continue;
            }
            String role = message.getRole();
            if ("assistant".equals(role)) {
                List<Map<String, Object>> nativeSteps = storedSteps(message);
                if (nativeSteps != null) {
                    input.addAll(nativeSteps);
                    continue;
                }
                if (message.getContent() != null && !message.getContent().isEmpty()) {
                    input.add(Map.of("type", "model_output",
                        "content", List.of(textContent(message.getContent()))));
                }
                if (message.getToolCalls() != null) {
                    for (ToolCall call : message.getToolCalls()) {
                        if (call == null || blank(call.getId()) || blank(call.getName())) {
                            continue;
                        }
                        Map<String, Object> function = new LinkedHashMap<>();
                        function.put("type", "function_call");
                        function.put("id", call.getId());
                        function.put("name", call.getName());
                        function.put("arguments", parseArguments(call.getArguments()));
                        input.add(function);
                    }
                }
            } else if ("tool".equals(role)) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("type", "function_result");
                result.put("call_id", message.getToolCallId() == null ? "" : message.getToolCallId());
                result.put("result", message.getContent() == null ? "" : message.getContent());
                input.add(result);
            } else {
                String text = message.getContent() == null ? "" : message.getContent();
                if ("system".equals(role)) {
                    text = "[System reminder]\n" + text;
                }
                List<Map<String, Object>> content = prepared.getOrDefault(message,
                    List.of(textContent(text)));
                input.add(Map.of("type", "user_input", "content", content));
            }
        }
        if (input.isEmpty()) {
            input.add(Map.of("type", "user_input", "content", List.of(textContent(""))));
        }
        return input;
    }

    private List<Map<String, Object>> userContent(ChatMessage message,
        ProviderModelCapabilities.Profile profile, InlineBudget budget) {
        List<Map<String, Object>> content = new ArrayList<>();
        if (message.getContent() != null && !message.getContent().isEmpty()) {
            content.add(textContent(message.getContent()));
        }
        for (MessageAttachment attachment : message.getAttachments()) {
            String name = AttachmentPayloadLoader.displayName(attachment);
            String visiblePath = AttachmentPayloadLoader.visiblePath(attachment, uploadsRoot);
            String mime = attachment == null || attachment.getMime() == null
                ? "application/octet-stream" : attachment.getMime().toLowerCase(Locale.ROOT);
            boolean image = IMAGE_MIMES.contains(mime);
            boolean document = DOCUMENT_MIMES.contains(mime);
            boolean audio = AUDIO_MIMES.contains(mime);
            boolean video = VIDEO_MIMES.contains(mime);
            boolean supported = attachmentsEnabled && (image && profile.imageInput()
                || document && profile.fileInput() || audio && profile.supportsModality("audio")
                || video && profile.supportsModality("video"));
            if (!supported) {
                content.add(textContent("[attachment " + name
                    + " was not sent inline; use file_read/OCR on " + visiblePath + "]"));
                continue;
            }
            AttachmentPayloadLoader.Loaded loaded = loadAttachment(attachment, budget);
            if (loaded == null) {
                content.add(textContent("[attachment " + name
                    + " was not sent inline: too large, unsafe, or unreadable; use file_read/OCR on "
                    + visiblePath + "]"));
                continue;
            }
            Map<String, Object> part = new LinkedHashMap<>();
            part.put("type", image ? "image" : document ? "document" : audio ? "audio" : "video");
            part.put("data", Base64.getEncoder().encodeToString(loaded.bytes()));
            part.put("mime_type", mime);
            content.add(part);
        }
        if (content.isEmpty()) {
            content.add(textContent(""));
        }
        return content;
    }

    private AttachmentPayloadLoader.Loaded loadAttachment(MessageAttachment attachment, InlineBudget budget) {
        if (!budget.canAccept()) {
            return null;
        }
        AttachmentPayloadLoader.Loaded loaded = AttachmentPayloadLoader.load(attachment, uploadsRoot,
            Math.min(maxAttachmentBytes, budget.remaining()));
        return loaded != null && budget.consume(loaded.bytes().length) ? loaded : null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> storedSteps(ChatMessage message) {
        if (message == null || blank(message.getResponseOutputItems())
            || !responseStateProducedHere(message.getResponseOutputProvider())) {
            return null;
        }
        try {
            byte[] raw = message.getResponseOutputItems().getBytes(StandardCharsets.UTF_8);
            if (raw.length > MAX_NATIVE_STEPS_BYTES) {
                return null;
            }
            List<Map<String, Object>> steps = M.readValue(raw, new TypeReference<>() {});
            if (steps == null || steps.isEmpty() || steps.size() > MAX_NATIVE_STEPS
                || steps.stream().anyMatch(step -> step == null || blank(String.valueOf(step.get("type"))))) {
                return null;
            }
            return steps;
        } catch (Exception invalid) {
            log.warn("Gemini: could not parse stored native steps for replay: {}", invalid.getMessage());
            return null;
        }
    }

    private static List<Map<String, Object>> toGeminiTools(List<Map<String, Object>> requested) {
        if (requested == null || requested.isEmpty()) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> tool : requested) {
            if (tool == null) {
                continue;
            }
            Object nested = tool.get("function");
            Map<?, ?> function = nested instanceof Map<?, ?> map ? map : tool;
            Object name = function.get("name");
            if (name == null || name.toString().isBlank()) {
                continue;
            }
            Map<String, Object> converted = new LinkedHashMap<>();
            converted.put("type", "function");
            converted.put("name", name.toString());
            if (function.get("description") != null) {
                converted.put("description", function.get("description"));
            }
            converted.put("parameters", function.get("parameters") instanceof Map<?, ?>
                ? function.get("parameters") : Map.of("type", "object", "properties", Map.of()));
            out.add(converted);
        }
        return out;
    }

    private static boolean isLocalWebSearch(Map<String, Object> tool) {
        return tool != null && "function".equals(tool.get("type")) && "web_search".equals(tool.get("name"));
    }

    private void processEvent(JsonNode event, StreamState state, AtomicBoolean firstContentSeen,
        Consumer<StreamEvent> onEvent) {
        String type = event.path("event_type").asText("");
        switch (type) {
            case "step.start" -> {
                int index = checkedIndex(event.path("index").asInt(-1));
                Map<String, Object> step = M.convertValue(event.path("step"), new TypeReference<>() {});
                state.setStep(index, step);
                emitInitialStep(step, state, firstContentSeen, onEvent);
            }
            case "step.delta" -> {
                int index = checkedIndex(event.path("index").asInt(-1));
                state.applyDelta(index, event.path("delta"), firstContentSeen, onEvent);
            }
            case "step.stop" -> state.finishFunctionArgument(checkedIndex(event.path("index").asInt(-1)));
            case "interaction.completed" -> {
                JsonNode interaction = event.path("interaction");
                state.status = interaction.path("status").asText("");
                JsonNode usage = interaction.path("usage");
                if (usage.isObject()) {
                    state.usage = ProviderUsage.gemini(usage);
                }
            }
            default -> {
                // interaction.created/status_update and forward-compatible metadata events carry no output step.
            }
        }
    }

    private static void emitInitialStep(Map<String, Object> step, StreamState state,
        AtomicBoolean firstContentSeen, Consumer<StreamEvent> onEvent) {
        String type = String.valueOf(step.get("type"));
        if ("function_call".equals(type)) {
            firstContentSeen.set(true);
            return;
        }
        Object blocks = "thought".equals(type) ? step.get("summary") : step.get("content");
        if (blocks instanceof List<?> list) {
            for (Object block : list) {
                if (block instanceof Map<?, ?> map && "text".equals(map.get("type")) && map.get("text") != null) {
                    String text = String.valueOf(map.get("text"));
                    StringBuilder target = "thought".equals(type) ? state.reasoning : state.text;
                    appendBounded(target, text, "thought".equals(type) ? MAX_REASONING_CHARS : MAX_ASSISTANT_CHARS,
                        "Gemini " + type);
                    firstContentSeen.set(true);
                    onEvent.accept(StreamEvent.of("thought".equals(type) ? "thinking_delta" : "text_delta",
                        Map.of("text", text)));
                }
            }
        }
    }

    private Request buildRequest(Map<String, Object> body) {
        Request.Builder request = new Request.Builder().url(interactionsEndpoint(baseUrl))
            .post(RequestBody.create(json(body), JSON));
        if (!blank(apiKey)) {
            request.addHeader("x-goog-api-key", apiKey);
        }
        if (Boolean.TRUE.equals(body.get("stream"))) {
            request.addHeader("Accept", "text/event-stream");
        }
        return request.build();
    }

    static String interactionsEndpoint(String configuredBase) {
        String base = configuredBase == null ? "" : configuredBase.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/interactions")) {
            return base;
        }
        if (base.matches(".*/v1beta")) {
            return base + "/interactions";
        }
        return base + "/v1beta/interactions";
    }

    private ProviderModelCapabilities.Profile requireGeminiProfile(String model) {
        ProviderModelCapabilities.Profile profile = modelCapabilities.profile(model);
        if (!profile.geminiApi()) {
            throw new ProviderException("config",
                model + " is not configured for the Gemini Interactions API");
        }
        return profile;
    }

    private String effectiveModel(String requested) {
        String model = blank(requested) ? defaultModel : requested;
        if (blank(model)) {
            throw new ProviderException("config", "Gemini model is required");
        }
        return model;
    }

    private String responseProviderId() {
        // Gemini explicitly permits stateless thought replay across model switches on the same endpoint/account.
        return "gemini:" + name;
    }

    private boolean responseStateProducedHere(String provider) {
        return responseProviderId().equals(provider);
    }

    private static ProviderException streamError(JsonNode event) {
        JsonNode error = event.path("error");
        String code = error.path("code").asText("500");
        return new ProviderException(code.isBlank() ? "500" : code,
            error.path("message").asText("Gemini interaction failed"));
    }

    private static boolean isSuccessfulStatus(String status) {
        return "completed".equals(status) || "requires_action".equals(status);
    }

    private static int checkedIndex(int index) {
        if (index < 0 || index >= MAX_NATIVE_STEPS) {
            throw new ProviderException("agent_oversize", "Gemini step index out of range: " + index);
        }
        return index;
    }

    private static Object parseArguments(String arguments) {
        try {
            JsonNode parsed = M.readTree(blank(arguments) ? "{}" : arguments);
            if (!parsed.isObject()) {
                throw new IllegalArgumentException("function arguments must be a JSON object");
            }
            return M.convertValue(parsed, Object.class);
        } catch (Exception invalid) {
            return Map.of();
        }
    }

    private static String outputText(JsonNode steps) {
        StringBuilder out = new StringBuilder();
        if (steps.isArray()) {
            for (JsonNode step : steps) {
                if (!"model_output".equals(step.path("type").asText())) {
                    continue;
                }
                for (JsonNode content : step.path("content")) {
                    if ("text".equals(content.path("type").asText())) {
                        appendBounded(out, content.path("text").asText(""), MAX_ASSISTANT_CHARS,
                            "Gemini oneShot output");
                    }
                }
            }
        }
        return out.toString();
    }

    private static String nativeStepsJson(List<Map<String, Object>> steps) {
        if (steps.size() > MAX_NATIVE_STEPS) {
            throw new ProviderException("agent_oversize", "Gemini native output exceeded " + MAX_NATIVE_STEPS
                + " steps");
        }
        try {
            byte[] json = M.writeValueAsBytes(steps);
            if (json.length > MAX_NATIVE_STEPS_BYTES) {
                throw new ProviderException("agent_oversize",
                    "Gemini native output exceeded " + MAX_NATIVE_STEPS_BYTES + " bytes");
            }
            return new String(json, StandardCharsets.UTF_8);
        } catch (IOException invalid) {
            throw new ProviderException("parse", "Could not serialize Gemini native output: " + invalid.getMessage());
        }
    }

    private static Map<String, Object> textContent(String text) {
        return Map.of("type", "text", "text", text == null ? "" : text);
    }

    private static String json(Object value) {
        try {
            return M.writeValueAsString(value);
        } catch (Exception invalid) {
            throw new ProviderException("parse", "Could not serialize Gemini request: " + invalid.getMessage());
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static List<ChatMessage> safeMessages(List<ChatMessage> messages) {
        return messages == null ? List.of() : messages;
    }

    private static final class StreamState {
        private final List<Map<String, Object>> steps = new ArrayList<>();
        private final Map<Integer, StringBuilder> argumentBuffers = new LinkedHashMap<>();
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private String status;
        private ProviderTokenUsage usage;

        private void setStep(int index, Map<String, Object> step) {
            while (steps.size() <= index) {
                steps.add(null);
            }
            if (step == null || blank(String.valueOf(step.get("type")))) {
                throw new ProviderException("parse", "Gemini step.start omitted step.type");
            }
            steps.set(index, new LinkedHashMap<>(step));
            if ("function_call".equals(step.get("type"))) {
                argumentBuffers.put(index, new StringBuilder());
            }
        }

        @SuppressWarnings("unchecked")
        private void applyDelta(int index, JsonNode delta, AtomicBoolean firstContentSeen,
            Consumer<StreamEvent> onEvent) {
            Map<String, Object> step = requireStep(index);
            String deltaType = delta.path("type").asText("");
            switch (deltaType) {
                case "text" -> {
                    String value = delta.path("text").asText("");
                    appendBounded(text, value, MAX_ASSISTANT_CHARS, "Gemini text");
                    ((List<Map<String, Object>>)step.computeIfAbsent("content", ignored -> new ArrayList<>()))
                        .add(textContent(value));
                    firstContentSeen.set(true);
                    onEvent.accept(StreamEvent.of("text_delta", Map.of("text", value)));
                }
                case "thought_summary" -> {
                    Map<String, Object> content =
                        M.convertValue(delta.path("content"), new TypeReference<>() {});
                    ((List<Map<String, Object>>)step.computeIfAbsent("summary", ignored -> new ArrayList<>()))
                        .add(content);
                    if ("text".equals(content.get("type")) && content.get("text") != null) {
                        String value = String.valueOf(content.get("text"));
                        appendBounded(reasoning, value, MAX_REASONING_CHARS, "Gemini thought summary");
                        firstContentSeen.set(true);
                        onEvent.accept(StreamEvent.of("thinking_delta", Map.of("text", value)));
                    }
                }
                case "thought_signature" -> step.put("signature", delta.path("signature").asText(""));
                case "arguments_delta" -> {
                    String chunk = delta.path("arguments").asText("");
                    StringBuilder arguments = argumentBuffers.computeIfAbsent(index, ignored -> new StringBuilder());
                    appendBounded(arguments, chunk, MAX_TOOL_ARGS_CHARS, "Gemini function arguments");
                    firstContentSeen.set(true);
                }
                case "image", "audio" -> {
                    Map<String, Object> content = M.convertValue(delta, new TypeReference<>() {});
                    ((List<Map<String, Object>>)step.computeIfAbsent("content", ignored -> new ArrayList<>()))
                        .add(content);
                    firstContentSeen.set(true);
                }
                default -> mergeGenericDelta(step, delta);
            }
        }

        private void finishFunctionArgument(int index) {
            Map<String, Object> step = requireStep(index);
            if (!"function_call".equals(step.get("type"))) {
                return;
            }
            String raw = argumentBuffers.getOrDefault(index, new StringBuilder()).toString();
            if (raw.isBlank()) {
                step.putIfAbsent("arguments", Map.of());
                return;
            }
            try {
                JsonNode parsed = M.readTree(raw);
                if (!parsed.isObject()) {
                    throw new IOException("arguments are not a JSON object");
                }
                step.put("arguments", M.convertValue(parsed, Object.class));
            } catch (Exception invalid) {
                throw new ProviderException("incomplete",
                    "Gemini function_call ended with invalid arguments: " + invalid.getMessage());
            }
        }

        private void finishFunctionArguments() {
            for (Integer index : new ArrayList<>(argumentBuffers.keySet())) {
                finishFunctionArgument(index);
            }
            steps.removeIf(Objects::isNull);
        }

        private List<ToolCall> toolCalls() {
            List<ToolCall> calls = new ArrayList<>();
            for (Map<String, Object> step : steps) {
                if (step == null || !"function_call".equals(step.get("type"))) {
                    continue;
                }
                String id = String.valueOf(step.get("id"));
                String name = String.valueOf(step.get("name"));
                if (blank(id) || blank(name) || "null".equals(id) || "null".equals(name)) {
                    throw new ProviderException("incomplete", "Gemini function_call omitted id or name");
                }
                calls.add(ToolCall.builder().id(id).name(name)
                    .arguments(json(step.getOrDefault("arguments", Map.of()))).build());
            }
            return calls;
        }

        private Map<String, Object> requireStep(int index) {
            if (index >= steps.size() || steps.get(index) == null) {
                throw new ProviderException("parse", "Gemini delta referenced unknown step index " + index);
            }
            return steps.get(index);
        }

        private static void mergeGenericDelta(Map<String, Object> step, JsonNode delta) {
            if (!delta.isObject()) {
                return;
            }
            delta.fields().forEachRemaining(entry -> {
                if (!"type".equals(entry.getKey())) {
                    step.put(entry.getKey(), M.convertValue(entry.getValue(), Object.class));
                }
            });
        }
    }

    private static final class InlineBudget {
        private long bytes;
        private final int maxCount;
        private int count;

        private InlineBudget(long bytes, int maxCount) {
            this.bytes = Math.max(0L, bytes);
            this.maxCount = Math.max(0, maxCount);
        }

        private boolean canAccept() {
            return bytes > 0 && count < maxCount;
        }

        private long remaining() {
            return bytes;
        }

        private boolean consume(long amount) {
            if (amount < 0 || amount > bytes || count >= maxCount) {
                return false;
            }
            bytes -= amount;
            count++;
            return true;
        }
    }
}
