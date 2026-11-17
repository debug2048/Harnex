package com.harnex.provider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.harnex.observability.HarnexMetrics;
import com.harnex.model.ChatMessage;
import com.harnex.model.MessageAttachment;
import com.harnex.model.StreamEvent;
import com.harnex.model.ToolCall;
import com.harnex.net.HttpExchangeState;
import com.harnex.net.ProxyResolution;

import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Native OpenAI <b>Responses API</b> provider ({@code POST {baseUrl}/responses}).
 *
 * <p>
 * Distinct from {@link OpenAiCompatibleProvider} (Chat Completions): the Responses API is OpenAI's current recommended
 * surface for reasoning models (GPT-5.x / o-series). Key shape differences this adapter handles:
 * <ul>
 * <li>{@code input} (array of typed items) instead of {@code messages}; {@code instructions} for the system
 * prompt.</li>
 * <li>Typed semantic stream events ({@code response.output_text.delta}, {@code response.function_call_arguments.delta},
 * {@code response.reasoning_summary_text.delta}, {@code response.completed}) keyed by the payload {@code type}.</li>
 * <li>Function calls are {@code function_call} items with a {@code call_id}; tool results are
 * {@code function_call_output} items — both top-level in {@code input}.</li>
 * <li>{@code max_output_tokens} instead of {@code max_tokens}.</li>
 * </ul>
 *
 * <p>
 * <b>Stateless reasoning</b>: this adapter runs with {@code store:false} +
 * {@code include:["reasoning.encrypted_content"]} so the harness stays the single source of truth (compatible with
 * failover and ZDR). Every provider-native output item is captured at the terminal response event and replayed
 * verbatim on the next turn. This preserves encrypted reasoning as well as item ids, ordering, phase/caller linkage
 * and provider item types that cannot be reconstructed from display text and normalized tool calls; see
 * {@link #toApiInput}.
 */
@Slf4j
public class OpenAiResponsesProvider extends AbstractHttpProvider {

    private static final Set<String> SUPPORTED_IMAGE_MIMES =
        Set.of("image/png", "image/jpeg", "image/webp", "image/gif");
    private static final Set<String> SUPPORTED_FILE_MIMES = Set.of("application/pdf", "text/plain", "text/markdown",
        "text/csv", "application/json", "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation", "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    /** Keep raw inline bytes below the common 32MB JSON request ceiling after base64 expansion and prompt overhead. */
    private static final long MAX_INLINE_REQUEST_BYTES = 20L * 1024 * 1024;
    private static final int MAX_NATIVE_OUTPUT_ITEMS = 256;
    private static final int MAX_NATIVE_OUTPUT_BYTES = 8 * 1024 * 1024;
    private static final int MAX_FUNCTION_CALL_ITEMS = 256;
    public static final String REASONING_PROVIDER = "openai-responses";

    private final Options opts;
    private final ProviderModelCapabilities modelCapabilities;
    private final boolean visionEnabled;
    /** Lower-case exact model ids explicitly configured with input modality {@code image}. */
    private final Set<String> imageInputModels;
    /** Lower-case exact model ids explicitly configured with input modality {@code file}. */
    private final Set<String> fileInputModels;
    private final long maxImageBytes;
    private final String visionDetail;
    private final int maxImagesPerMessage;
    private final Path uploadsRoot;
    /** Stable per-provider session ID used for Codex emulation. */
    private final String sessionId = UUID.randomUUID().toString();

    /**
     * Per-provider request-shape configuration assembled from {@link ResolvedProvider} by
     * {@link ProviderRouter}.
     */
    public record Options(int defaultMaxTokens, String effort, boolean reasoning, boolean sendTemperature,
        boolean nativeWebSearch, boolean emulation, String emulationVersion, boolean promptCacheKey,
        boolean promptCacheExplicit) {
        public static Options defaults() {
            return new Options(0, null, false, false);
        }

        public String codexVersion() {
            return (emulationVersion == null || emulationVersion.isBlank()) ? "0.142.5" : emulationVersion;
        }

        public Options(int defaultMaxTokens, String effort, boolean reasoning, boolean sendTemperature) {
            this(defaultMaxTokens, effort, reasoning, sendTemperature, false, false, null, true, false);
        }

        public Options(int defaultMaxTokens, String effort, boolean reasoning, boolean sendTemperature,
            boolean nativeWebSearch) {
            this(defaultMaxTokens, effort, reasoning, sendTemperature, nativeWebSearch, false, null, true, false);
        }
    }

    public OpenAiResponsesProvider(String name, String baseUrl, String apiKey, String defaultModel,
        HarnexMetrics metrics) {
        this(name, baseUrl, apiKey, defaultModel, metrics, null, Options.defaults());
    }

    public OpenAiResponsesProvider(String name, String baseUrl, String apiKey, String defaultModel,
        HarnexMetrics metrics, ProxyResolution.Resolved proxy, Options opts) {
        this(name, baseUrl, apiKey, defaultModel, false, Set.of(), 0, "auto", 0, null, metrics, proxy, opts);
    }

    public OpenAiResponsesProvider(String name, String baseUrl, String apiKey, String defaultModel,
        HarnexMetrics metrics, ProxyResolution.Resolved proxy, Options opts,
        ProviderModelCapabilities modelCapabilities) {
        this(name, baseUrl, apiKey, defaultModel, false, Set.of(), Set.of(), 0, "auto", 0, null, metrics, proxy,
            opts, modelCapabilities);
    }

    public OpenAiResponsesProvider(String name, String baseUrl, String apiKey, String defaultModel,
        boolean visionEnabled, Set<String> imageInputModels, long maxImageBytes, String visionDetail,
        int maxImagesPerMessage, Path uploadsRoot, HarnexMetrics metrics, ProxyResolution.Resolved proxy, Options opts) {
        this(name, baseUrl, apiKey, defaultModel, visionEnabled, imageInputModels, Set.of(), maxImageBytes,
            visionDetail, maxImagesPerMessage, uploadsRoot, metrics, proxy, opts);
    }

    public OpenAiResponsesProvider(String name, String baseUrl, String apiKey, String defaultModel,
        boolean visionEnabled, Set<String> imageInputModels, Set<String> fileInputModels, long maxAttachmentBytes,
        String visionDetail, int maxAttachmentsPerMessage, Path uploadsRoot, HarnexMetrics metrics,
        ProxyResolution.Resolved proxy, Options opts) {
        this(name, baseUrl, apiKey, defaultModel, visionEnabled, imageInputModels, fileInputModels,
            maxAttachmentBytes, visionDetail, maxAttachmentsPerMessage, uploadsRoot, metrics, proxy, opts,
            ProviderModelCapabilities.conservative("openai-responses"));
    }

    /** Full constructor used by {@link ProviderRouter}; capabilities are resolved once at startup. */
    public OpenAiResponsesProvider(String name, String baseUrl, String apiKey, String defaultModel,
        boolean visionEnabled, Set<String> imageInputModels, Set<String> fileInputModels, long maxAttachmentBytes,
        String visionDetail, int maxAttachmentsPerMessage, Path uploadsRoot, HarnexMetrics metrics,
        ProxyResolution.Resolved proxy, Options opts, ProviderModelCapabilities modelCapabilities) {
        super(name, (baseUrl == null || baseUrl.isBlank()) ? "https://api.openai.com/v1" : baseUrl, apiKey,
            defaultModel, metrics, proxy);
        this.opts = opts == null ? Options.defaults() : opts;
        this.modelCapabilities = modelCapabilities == null
            ? ProviderModelCapabilities.conservative("openai-responses") : modelCapabilities;
        this.visionEnabled = visionEnabled;
        this.imageInputModels = normaliseModelIds(imageInputModels);
        this.fileInputModels = normaliseModelIds(fileInputModels);
        this.maxImageBytes = maxAttachmentBytes;
        this.visionDetail = visionDetail == null || visionDetail.isBlank() ? "auto" : visionDetail;
        this.maxImagesPerMessage = Math.max(0, maxAttachmentsPerMessage);
        this.uploadsRoot = uploadsRoot;
    }

    @Override
    public void streamChat(ChatRequest req, Consumer<StreamEvent> onEvent) {
        streamChat(req, onEvent, 0L);
    }

    @Override
    public void streamChat(ChatRequest req, Consumer<StreamEvent> onEvent, long ttftBudgetMs) {
        String requestModel = req.getModel() == null ? defaultModel : req.getModel();
        Map<String, Object> body = buildBody(req, true);

        // WebSocket transport for Codex emulation mode
        if (opts.emulation()) {
            streamChatViaWebSocket(body, req, onEvent, ttftBudgetMs);
            return;
        }

        Request httpReq = buildRequest(body, req.getIdempotencyKey());

        // DEBUG-only full request/response logging, correlated by requestId (URL + body only, no headers).
        String requestId = ProviderStreamLogger.newRequestId();
        long startNanos = System.nanoTime();
        if (log.isDebugEnabled()) {
            ProviderStreamLogger.logRequest(log, requestId, name, httpReq.url().toString(), toJson(body));
        }

        StringBuilder assistantText = new StringBuilder();
        StringBuilder thinkingText = new StringBuilder();
        StringBuilder refusalText = new StringBuilder();
        // item_id -> {call_id, name, arguments StringBuilder} for streamed function calls
        Map<String, Map<String, Object>> partialTools = new LinkedHashMap<>();
        Set<String> completedFunctionIds = new HashSet<>();
        AtomicLong promptTokens = new AtomicLong();
        AtomicLong completionTokens = new AtomicLong();
        ProviderTokenUsage[] usageDetail = {null};
        String[] reasoningItemsJson = {null};
        String[] responseOutputItemsJson = {null};
        String[] incompleteReason = {null};
        AtomicBoolean messageStarted = new AtomicBoolean(false);
        AtomicBoolean refusalSeen = new AtomicBoolean(false);
        AtomicBoolean terminalSeen = new AtomicBoolean(false);

        Call call = newCall(httpReq);
        AtomicBoolean firstContentSeen = new AtomicBoolean(false);
        long ttftMs = ttftBudgetMs > 0 ? Math.min(ttftBudgetMs, STREAM_TTFT_MS) : STREAM_TTFT_MS;

        ProviderException pendingErr = readSseStream(call, ttftMs, firstContentSeen, "network", () -> {
            onEvent.accept(StreamEvent.of("message_start",
                Map.of("provider", name, "model", requestModel)));
            messageStarted.set(true);
        }, payload -> {
            JsonNode node = M.readTree(payload);
            String type = node.path("type").asText("");
            if (isTerminalEvent(type)) {
                terminalSeen.set(true);
            }
            processResponseEvent(node, assistantText, thinkingText, refusalText, partialTools, completedFunctionIds,
                firstContentSeen,
                promptTokens, completionTokens, usageDetail, reasoningItemsJson, responseOutputItemsJson, incompleteReason,
                refusalSeen, onEvent);
            return terminalSeen.get();
        });
        if (pendingErr == null && !terminalSeen.get()) {
            pendingErr = new ProviderException("network",
                "Responses stream ended before response.completed/response.incomplete/response.failed/error");
        } else if (pendingErr == null && incompleteReason[0] != null) {
            pendingErr = new ProviderException("incomplete",
                "Responses stream ended incomplete: " + incompleteReason[0]);
        }

        long pt = promptTokens.get();
        long ct = completionTokens.get();
        if (pt > 0 || ct > 0) {
            ProviderTokenUsage detail = usageDetail[0] == null
                ? new ProviderTokenUsage(pt, ct, 0, 0, 0) : usageDetail[0];
            Map<String, Object> u = new LinkedHashMap<>(detail.eventData());
            u.put("model", req.getModel() == null ? defaultModel : req.getModel());
            u.put("provider", name);
            onEvent.accept(StreamEvent.of("usage", u));
            detail.record(metrics);
        }

        List<ToolCall> toolCalls = new ArrayList<>();
        boolean executableToolResult = pendingErr == null && incompleteReason[0] == null && !refusalSeen.get();
        for (Map.Entry<String, Map<String, Object>> e : executableToolResult ? partialTools.entrySet() : Set
            .<Map.Entry<String, Map<String, Object>>>of()) {
            Map<String, Object> s = e.getValue();
            if (!completedFunctionIds.contains(e.getKey())) {
                pendingErr = new ProviderException("incomplete",
                    "Responses function_call item " + e.getKey() + " ended before its done event");
                toolCalls.clear();
                break;
            }
            String tname = String.valueOf(s.get("name"));
            if (tname == null || tname.isBlank() || "null".equals(tname)) {
                pendingErr = new ProviderException("incomplete",
                    "Responses function_call item " + e.getKey() + " ended without name");
                toolCalls.clear();
                break;
            }
            String callId = String.valueOf(s.get("call_id"));
            if (callId.isBlank() || "null".equals(callId)) {
                pendingErr = new ProviderException("incomplete",
                    "Responses function_call item " + e.getKey() + " ended without call_id");
                toolCalls.clear();
                break;
            }
            String args = s.get("arguments").toString();
            if (args.isEmpty()) {
                args = "{}";
            }
            try {
                M.readTree(args); // always validate — a truncated stream leaves invalid partial JSON
            } catch (Exception jpe) {
                pendingErr = new ProviderException("incomplete", "Responses function_call item " + e.getKey()
                    + " (name=" + tname + ") ended with invalid arguments: " + jpe.getMessage());
                toolCalls.clear();
                break;
            }
            toolCalls.add(ToolCall.builder().id(callId).name(tname).arguments(args).build());
        }

        if (messageStarted.get()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("content", assistantText.toString());
            payload.put("toolCalls", toolCalls);
            if (thinkingText.length() > 0) {
                payload.put("reasoning", thinkingText.toString());
            }
            if (pendingErr == null && incompleteReason[0] == null && reasoningItemsJson[0] != null) {
                payload.put("reasoningProvider", responseProviderId(requestModel));
                payload.put("reasoningToken", reasoningItemsJson[0]);
            }
            if (pendingErr == null && incompleteReason[0] == null && responseOutputItemsJson[0] != null) {
                payload.put("responseOutputProvider", responseProviderId(requestModel));
                payload.put("responseOutputItems", responseOutputItemsJson[0]);
            }
            if (refusalSeen.get()) {
                payload.put("refusal", true);
            }
            if (pendingErr != null) {
                payload.put("interrupted", true);
                payload.put("error", pendingErr.code);
            }
            if (incompleteReason[0] != null) {
                payload.put("interrupted", true);
                payload.put("incompleteReason", incompleteReason[0]);
            }
            onEvent.accept(StreamEvent.of("message_end", payload));
        }
        if (log.isDebugEnabled()) {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            boolean interrupted = pendingErr != null || incompleteReason[0] != null;
            String stopStr = pendingErr != null ? pendingErr.code
                : incompleteReason[0] != null ? incompleteReason[0] : "stop";
            ProviderStreamLogger.logResponse(log, requestId, name, assistantText.toString(), thinkingText.toString(),
                toolCalls, stopStr, interrupted, durationMs);
        }
        if (pendingErr != null) {
            throw withExchange(pendingErr, call);
        }
    }

    // ─── Shared event processing (SSE + WebSocket) ─────────────────────────────────────────────

    /**
     * Process a single Responses API streaming event. Used by both the SSE and WebSocket transport paths.
     */
    private void processResponseEvent(JsonNode node, StringBuilder assistantText, StringBuilder thinkingText,
        StringBuilder refusalText, Map<String, Map<String, Object>> partialTools, Set<String> completedFunctionIds,
        AtomicBoolean firstContentSeen,
        AtomicLong promptTokens, AtomicLong completionTokens, ProviderTokenUsage[] usageDetail,
        String[] reasoningItemsJson,
        String[] responseOutputItemsJson, String[] incompleteReason, AtomicBoolean refusalSeen,
        Consumer<StreamEvent> onEvent) throws IOException {
        String type = node.path("type").asText("");
        switch (type) {
            case "response.output_text.delta" -> {
                String t = node.path("delta").asText("");
                if (!t.isEmpty()) {
                    firstContentSeen.set(true);
                    if (assistantText.length() + t.length() > MAX_ASSISTANT_CHARS) {
                        throw new ProviderException("agent_oversize",
                            "response exceeded " + MAX_ASSISTANT_CHARS + " chars");
                    }
                    assistantText.append(t);
                    onEvent.accept(StreamEvent.of("text_delta", Map.of("text", t)));
                }
            }
            case "response.reasoning_summary_text.delta" -> {
                String t = node.path("delta").asText("");
                if (!t.isEmpty()) {
                    firstContentSeen.set(true);
                    appendBounded(thinkingText, t, MAX_REASONING_CHARS, "reasoning response");
                    onEvent.accept(StreamEvent.of("thinking_delta", Map.of("text", t)));
                }
            }
            case "response.refusal.delta" -> appendRefusal(node.path("delta").asText(""), false, assistantText,
                refusalText, firstContentSeen, refusalSeen, onEvent);
            case "response.refusal.done" -> appendRefusal(node.path("refusal").asText(""), true, assistantText,
                refusalText, firstContentSeen, refusalSeen, onEvent);
            case "response.content_part.done" -> captureRefusalParts(node.path("part"), assistantText, refusalText,
                firstContentSeen, refusalSeen, onEvent);
            case "response.output_item.added" -> {
                JsonNode item = node.path("item");
                if ("function_call".equals(item.path("type").asText())) {
                    firstContentSeen.set(true);
                    String itemId = item.path("id").asText("");
                    requireToolSlotCapacity(partialTools, itemId);
                    Map<String, Object> slot = partialTools.computeIfAbsent(itemId, k -> {
                        Map<String, Object> s = new LinkedHashMap<>();
                        s.put("arguments", new StringBuilder());
                        return s;
                    });
                    slot.put("call_id", item.path("call_id").asText(item.path("id").asText("call_" + itemId)));
                    slot.put("name", item.path("name").asText(""));
                    String seedArgs = item.path("arguments").asText("");
                    if (!seedArgs.isEmpty()) {
                        requireToolArgumentsSize(seedArgs, partialTools);
                        ((StringBuilder)slot.get("arguments")).append(seedArgs);
                    }
                }
            }
            case "response.output_item.done" -> {
                JsonNode item = node.path("item");
                String completedId = captureCompletedFunctionCall(item, partialTools);
                if (completedId != null) {
                    if (completedItemStatus(item)) {
                        completedFunctionIds.add(completedId);
                    } else {
                        // output_item.done is authoritative over an earlier arguments.done frame.
                        completedFunctionIds.remove(completedId);
                    }
                }
                captureRefusalParts(item, assistantText, refusalText, firstContentSeen, refusalSeen, onEvent);
            }
            case "response.function_call_arguments.delta" -> {
                String itemId = node.path("item_id").asText("");
                requireToolSlotCapacity(partialTools, itemId);
                Map<String, Object> slot = partialTools.computeIfAbsent(itemId, k -> {
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("call_id", "call_" + k);
                    s.put("name", "");
                    s.put("arguments", new StringBuilder());
                    return s;
                });
                StringBuilder sb = (StringBuilder)slot.get("arguments");
                String chunk = node.path("delta").asText("");
                if (sb.length() + chunk.length() > MAX_TOOL_ARGS_CHARS) {
                    partialTools.clear();
                    throw new ProviderException("agent_oversize",
                        "function_call arguments exceeded " + MAX_TOOL_ARGS_CHARS + " chars");
                }
                if (!chunk.isEmpty()) {
                    firstContentSeen.set(true);
                }
                sb.append(chunk);
            }
            case "response.function_call_arguments.done" -> {
                // The done event carries the full arguments — authoritative over accumulated deltas.
                String itemId = node.path("item_id").asText("");
                String full = node.path("arguments").asText("");
                requireToolArgumentsSize(full, partialTools);
                requireToolSlotCapacity(partialTools, itemId);
                Map<String, Object> slot = partialTools.computeIfAbsent(itemId, ignored -> {
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("call_id", "");
                    s.put("name", "");
                    s.put("arguments", new StringBuilder());
                    return s;
                });
                if (!full.isEmpty()) {
                    slot.put("arguments", new StringBuilder(full));
                }
                completedFunctionIds.add(itemId);
            }
            case "response.completed", "response.incomplete", "response.failed" -> {
                JsonNode resp = node.path("response");
                if ("response.incomplete".equals(type)) {
                    incompleteReason[0] = resp.path("incomplete_details").path("reason")
                        .asText("response_incomplete");
                }
                JsonNode usage = resp.path("usage");
                if (usage.isObject()) {
                    ProviderTokenUsage detail = ProviderUsage.responses(usage);
                    usageDetail[0] = detail;
                    promptTokens.set(detail.prompt());
                    completionTokens.set(detail.completion());
                }
                JsonNode responseOutput = resp.path("output");
                if ("response.completed".equals(type)) {
                    String responseStatus = resp.path("status").asText("");
                    if (!responseStatus.isBlank() && !"completed".equals(responseStatus)) {
                        throw new ProviderException("incomplete",
                            "response.completed carried status=" + responseStatus);
                    }
                }
                if (responseOutput.isArray() && !responseOutput.isEmpty()) {
                    // Preserve the entire typed output array. Reconstructing only text/function calls drops ids,
                    // phase/caller linkage and provider item types needed by manual stateless continuation.
                    responseOutputItemsJson[0] = boundedNativeItemsJson(responseOutput, "response output");
                }
                for (JsonNode item : responseOutput) {
                    String completedId = captureCompletedFunctionCall(item, partialTools);
                    if (completedId != null) {
                        if (completedItemStatus(item)) {
                            completedFunctionIds.add(completedId);
                        } else {
                            // The terminal output is authoritative; hosted/native items may legitimately fail, but an
                            // incomplete client function_call must never be executed locally.
                            completedFunctionIds.remove(completedId);
                        }
                    }
                }
                captureRefusalParts(responseOutput, assistantText, refusalText, firstContentSeen, refusalSeen, onEvent);
                if (!refusalSeen.get()) {
                    backfillTerminalOutputText(responseOutput, assistantText, firstContentSeen, onEvent);
                }
                List<JsonNode> rItems = new ArrayList<>();
                for (JsonNode item : responseOutput) {
                    if ("reasoning".equals(item.path("type").asText())) {
                        rItems.add(item);
                    }
                }
                if (!rItems.isEmpty()) {
                    reasoningItemsJson[0] = boundedNativeItemsJson(M.valueToTree(rItems), "reasoning output");
                }
                if ("response.failed".equals(type)) {
                    throw responseFailure(resp, "500", "responses stream failed");
                }
            }
            case "error" -> {
                String status = node.path("status").asText("");
                String code = status.matches("[1-5][0-9][0-9]") ? status : "500";
                throw responseFailure(node, code, "responses stream error");
            }
            default -> {
                /* response.created / .in_progress / unrelated item deltas / ignore */ }
        }
    }

    private static boolean isTerminalEvent(String type) {
        return "response.completed".equals(type) || "response.incomplete".equals(type)
            || "response.failed".equals(type) || "error".equals(type);
    }

    /** A done/terminal item is authoritative and also covers relays that omit the incremental function-call events. */
    private static String captureCompletedFunctionCall(JsonNode item,
        Map<String, Map<String, Object>> partialTools) {
        if (item == null || !"function_call".equals(item.path("type").asText())) {
            return null;
        }
        String itemId = item.path("id").asText("");
        if (itemId.isBlank()) {
            itemId = "terminal_" + partialTools.size();
        }
        String arguments = item.path("arguments").asText("{}");
        requireToolArgumentsSize(arguments, partialTools);
        requireToolSlotCapacity(partialTools, itemId);
        Map<String, Object> slot = partialTools.computeIfAbsent(itemId, ignored -> new LinkedHashMap<>());
        slot.put("call_id", item.path("call_id").asText(""));
        slot.put("name", item.path("name").asText(""));
        slot.put("arguments", new StringBuilder(arguments));
        return itemId;
    }

    private static boolean completedItemStatus(JsonNode item) {
        String status = item.path("status").asText("");
        return status.isBlank() || "completed".equals(status);
    }

    private static void requireToolArgumentsSize(String arguments,
        Map<String, Map<String, Object>> partialTools) {
        if (arguments != null && arguments.length() > MAX_TOOL_ARGS_CHARS) {
            partialTools.clear();
            throw new ProviderException("agent_oversize",
                "function_call arguments exceeded " + MAX_TOOL_ARGS_CHARS + " chars");
        }
    }

    private static void requireToolSlotCapacity(Map<String, Map<String, Object>> partialTools, String itemId) {
        if (!partialTools.containsKey(itemId) && partialTools.size() >= MAX_FUNCTION_CALL_ITEMS) {
            partialTools.clear();
            throw new ProviderException("agent_oversize",
                "too many function_call items (limit " + MAX_FUNCTION_CALL_ITEMS + ")");
        }
    }

    /** Recover text from the authoritative terminal output when an upstream omitted one or more delta events. */
    private void backfillTerminalOutputText(JsonNode output, StringBuilder assistantText,
        AtomicBoolean firstContentSeen, Consumer<StreamEvent> onEvent) {
        StringBuilder terminal = new StringBuilder();
        collectOutputText(output, terminal);
        if (terminal.length() == 0) {
            return;
        }
        String streamed = assistantText.toString();
        if (!terminal.toString().startsWith(streamed)) {
            throw new ProviderException("parse",
                "Responses terminal output_text did not match the streamed text prefix");
        }
        String missing = terminal.substring(streamed.length());
        if (missing.isEmpty()) {
            return;
        }
        if (assistantText.length() + missing.length() > MAX_ASSISTANT_CHARS) {
            throw new ProviderException("agent_oversize", "response exceeded " + MAX_ASSISTANT_CHARS + " chars");
        }
        firstContentSeen.set(true);
        assistantText.append(missing);
        onEvent.accept(StreamEvent.of("text_delta", Map.of("text", missing)));
    }

    private static void collectOutputText(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectOutputText(child, out));
            return;
        }
        if (node.isObject()) {
            if ("output_text".equals(node.path("type").asText()) && node.path("text").isTextual()) {
                out.append(node.path("text").asText());
                return;
            }
            JsonNode content = node.get("content");
            if (content != null) {
                collectOutputText(content, out);
            }
        }
    }

    /** WebSocket uses create-body fields beside a required event type; HTTP-only transport flags are invalid. */
    static Map<String, Object> webSocketCreateEvent(Map<String, Object> httpBody) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "response.create");
        if (httpBody != null) {
            httpBody.forEach((key, value) -> {
                if (!"stream".equals(key) && !"background".equals(key) && !"type".equals(key)) {
                    event.put(key, value);
                }
            });
        }
        return event;
    }

    /**
     * Surface refusal text through the existing visible-text channel while retaining an explicit marker in the event
     * payload. The {@code *.done} event carries the authoritative full text, so only emit a missing suffix after deltas.
     */
    private void appendRefusal(String text, boolean authoritative, StringBuilder assistantText,
        StringBuilder refusalText, AtomicBoolean firstContentSeen, AtomicBoolean refusalSeen,
        Consumer<StreamEvent> onEvent) {
        if (text == null || text.isEmpty()) {
            return;
        }
        refusalSeen.set(true);
        String addition = text;
        if (authoritative) {
            String accumulated = refusalText.toString();
            if (text.equals(accumulated) || accumulated.startsWith(text)) {
                return;
            }
            if (text.startsWith(accumulated)) {
                addition = text.substring(accumulated.length());
            } else if (!accumulated.isEmpty()) {
                // A conforming stream's deltas concatenate to the done value. Preserve both if a relay disagrees rather
                // than silently replacing text already delivered to the caller.
                log.warn("Responses refusal.done disagrees with accumulated refusal deltas; preserving final text");
                addition = "\n" + text;
            }
            refusalText.setLength(0);
            refusalText.append(text);
        } else {
            refusalText.append(text);
        }
        if (addition.isEmpty()) {
            return;
        }
        if (assistantText.length() + addition.length() > MAX_ASSISTANT_CHARS) {
            throw new ProviderException("agent_oversize", "response exceeded " + MAX_ASSISTANT_CHARS + " chars");
        }
        assistantText.append(addition);
        firstContentSeen.set(true);
        onEvent.accept(StreamEvent.of("text_delta", Map.of("text", addition, "refusal", true)));
    }

    /** Find refusal content in a content part, output item, or terminal response.output array. */
    private void captureRefusalParts(JsonNode node, StringBuilder assistantText, StringBuilder refusalText,
        AtomicBoolean firstContentSeen, AtomicBoolean refusalSeen, Consumer<StreamEvent> onEvent) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                captureRefusalParts(child, assistantText, refusalText, firstContentSeen, refusalSeen, onEvent);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        if ("refusal".equals(node.path("type").asText())) {
            appendRefusal(node.path("refusal").asText(""), true, assistantText, refusalText, firstContentSeen,
                refusalSeen, onEvent);
        }
        JsonNode content = node.get("content");
        if (content != null) {
            captureRefusalParts(content, assistantText, refusalText, firstContentSeen, refusalSeen, onEvent);
        }
    }

    private static ProviderException responseFailure(JsonNode container, String routingCode, String fallbackMessage) {
        JsonNode error = container.path("error");
        String message;
        String upstreamCode = "";
        if (error.isTextual()) {
            message = error.asText(fallbackMessage);
        } else {
            message = error.path("message").asText(container.path("message").asText(fallbackMessage));
            upstreamCode = error.path("code").asText("");
        }
        if (!upstreamCode.isEmpty()) {
            message = upstreamCode + ": " + message;
        }
        return new ProviderException(routingCode, message);
    }

    // ─── WebSocket transport (Codex emulation) ────────────────────────────────────────────────

    /**
     * Stream a chat request via WebSocket transport (used when Codex emulation is active). The Responses API WebSocket
     * protocol: connect with upgrade headers, send the request body as a text frame, receive streaming events as
     * individual text frames, server closes when done.
     */
    private void streamChatViaWebSocket(Map<String, Object> body, ChatRequest req,
        Consumer<StreamEvent> onEvent, long ttftBudgetMs) {
        String requestModel = req.getModel() == null ? defaultModel : req.getModel();
        // 1. Build WebSocket URL: https→wss, http→ws
        String httpUrl = resolveEndpoint(baseUrl, "/responses");
        String wsUrl = httpUrl.replaceFirst("^https://", "wss://").replaceFirst("^http://", "ws://");

        // 2. Build upgrade request with emulation headers
        Request.Builder rb = new Request.Builder().url(wsUrl);
        if (apiKey != null && !apiKey.isBlank()) {
            rb.addHeader("Authorization", "Bearer " + apiKey);
        }
        applyEmulationHeaders(rb);
        Request upgradeReq = rb.build();

        // DEBUG-only full request/response logging, correlated by requestId. The logRequest call is
        // deferred until the body JSON is serialised below (URL + body only, no headers).
        String requestId = ProviderStreamLogger.newRequestId();
        long startNanos = System.nanoTime();

        // 3. Shared state
        StringBuilder assistantText = new StringBuilder();
        StringBuilder thinkingText = new StringBuilder();
        StringBuilder refusalText = new StringBuilder();
        Map<String, Map<String, Object>> partialTools = new LinkedHashMap<>();
        Set<String> completedFunctionIds = new HashSet<>();
        AtomicLong promptTokens = new AtomicLong();
        AtomicLong completionTokens = new AtomicLong();
        ProviderTokenUsage[] usageDetail = {null};
        String[] reasoningItemsJson = {null};
        String[] responseOutputItemsJson = {null};
        String[] incompleteReason = {null};

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ProviderException> errorRef = new AtomicReference<>();
        AtomicBoolean firstContentSeen = new AtomicBoolean(false);
        AtomicBoolean messageStarted = new AtomicBoolean(false);
        AtomicBoolean refusalSeen = new AtomicBoolean(false);
        AtomicBoolean terminalSeen = new AtomicBoolean(false);

        String json;
        HttpExchangeState exchange = new HttpExchangeState();
        ProviderCallScope.Context callOwner = ProviderCallScope.current();
        try {
            json = M.writeValueAsString(webSocketCreateEvent(body));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        if (log.isDebugEnabled()) {
            ProviderStreamLogger.logRequest(log, requestId, name, upgradeReq.url().toString(), json);
        }

        // 4. Establish WebSocket + send request body on open
        WebSocket ws = http.newWebSocket(upgradeReq, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                if (callOwner != null && callOwner.isCancelled()) {
                    errorRef.compareAndSet(null, new ProviderException("cancelled", "run cancelled"));
                    webSocket.cancel(); latch.countDown(); return;
                }
                if (!webSocket.send(json)) {
                    errorRef.compareAndSet(null, new ProviderException("network", "WebSocket request was not queued"));
                    webSocket.cancel(); latch.countDown(); return;
                }
                exchange.sent();
                onEvent.accept(StreamEvent.of("message_start",
                    Map.of("provider", name, "model", requestModel)));
                messageStarted.set(true);
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    if (text.length() > MAX_SSE_LINE_CHARS) {
                        throw new ProviderException("agent_oversize",
                            "WebSocket event exceeded " + MAX_SSE_LINE_CHARS + " characters");
                    }
                    JsonNode node = M.readTree(text);
                    String type = node.path("type").asText("");
                    if ("response.created".equals(type)) exchange.accepted(node.path("response").path("id").asText(null));
                    // Any substantive event counts as content for TTFT
                    if (type.contains("delta") || type.contains("added") || type.contains("completed")
                        || type.contains("incomplete")) {
                        firstContentSeen.set(true);
                        exchange.outputReceived();
                    }
                    if (isTerminalEvent(type)) {
                        terminalSeen.set(true);
                    }
                    processResponseEvent(node, assistantText, thinkingText, refusalText, partialTools,
                        completedFunctionIds, firstContentSeen,
                        promptTokens, completionTokens, usageDetail, reasoningItemsJson, responseOutputItemsJson,
                        incompleteReason, refusalSeen, onEvent);
                    if (terminalSeen.get()) {
                        latch.countDown();
                        webSocket.close(1000, null);
                    }
                } catch (ProviderException pe) {
                    errorRef.compareAndSet(null, pe);
                    latch.countDown();
                    webSocket.close(1000, null);
                } catch (Exception e) {
                    errorRef.compareAndSet(null, ProviderException.fromCause("parse", e));
                    latch.countDown();
                    webSocket.close(1000, null);
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                String code = "network";
                ProviderException failure = ProviderException.fromCause(code, t);
                String msg = failure.getMessage();
                if (response != null) {
                    if (!messageStarted.get()) exchange.rejected(response);
                    code = String.valueOf(response.code());
                    try {
                        if (response.body() != null) {
                            msg = "HTTP " + code + ": " + readBoundedErrorBody(response);
                        }
                    } catch (Exception ignored) { /* best-effort body read */ }
                    failure = new ProviderException(code, msg);
                    failure.initCause(t);
                }
                errorRef.compareAndSet(null, failure);
                latch.countDown();
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                latch.countDown();
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(1000, null);
            }
        });

        // 5. TTFT watchdog
        long ttftMs = ttftBudgetMs > 0 ? Math.min(ttftBudgetMs, STREAM_TTFT_MS) : STREAM_TTFT_MS;
        ScheduledFuture<?> ttftWatchdog = STREAM_TTFT_EXEC.schedule(() -> {
            if (firstContentSeen.get()) {
                return;
            }
            log.warn("Provider {} WebSocket TTFT watchdog firing after {}ms — closing stalled connection", name, ttftMs);
            errorRef.compareAndSet(null, new ProviderException("stream_ttft_timeout",
                "no first content from " + name + " within " + ttftMs + "ms (WebSocket)"));
            ws.cancel();
            latch.countDown();
        }, ttftMs, TimeUnit.MILLISECONDS);

        // 6. Block until WebSocket closes or fails
        try (CallCancellation cancellation = new CallCancellation(() -> {
            errorRef.compareAndSet(null, new ProviderException("cancelled", "run cancelled"));
            ws.cancel(); latch.countDown();
        })) {
            if (!latch.await(10, TimeUnit.MINUTES)) {
                errorRef.compareAndSet(null, new ProviderException("timeout", "WebSocket stream timed out"));
                ws.cancel();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            errorRef.compareAndSet(null, new ProviderException("cancelled", "agent cancelled"));
            ws.cancel();
        } finally {
            ttftWatchdog.cancel(false);
            ProviderCallScope.exchange(exchange);
        }

        // 7. Post-processing — identical to SSE path
        ProviderException pendingErr = errorRef.get();
        if (pendingErr == null && !terminalSeen.get()) {
            pendingErr = new ProviderException("network",
                "Responses WebSocket closed before response.completed/response.incomplete/response.failed/error");
        } else if (pendingErr == null && incompleteReason[0] != null) {
            pendingErr = new ProviderException("incomplete",
                "Responses stream ended incomplete: " + incompleteReason[0]);
        }

        long pt = promptTokens.get();
        long ct = completionTokens.get();
        if (pt > 0 || ct > 0) {
            ProviderTokenUsage detail = usageDetail[0] == null
                ? new ProviderTokenUsage(pt, ct, 0, 0, 0) : usageDetail[0];
            Map<String, Object> u = new LinkedHashMap<>(detail.eventData());
            u.put("model", req.getModel() == null ? defaultModel : req.getModel());
            u.put("provider", name);
            onEvent.accept(StreamEvent.of("usage", u));
            detail.record(metrics);
        }

        List<ToolCall> toolCalls = new ArrayList<>();
        boolean executableToolResult = pendingErr == null && incompleteReason[0] == null && !refusalSeen.get();
        for (Map.Entry<String, Map<String, Object>> e : executableToolResult ? partialTools.entrySet() : Set
            .<Map.Entry<String, Map<String, Object>>>of()) {
            Map<String, Object> s = e.getValue();
            if (!completedFunctionIds.contains(e.getKey())) {
                pendingErr = new ProviderException("incomplete",
                    "Responses function_call item " + e.getKey() + " ended before its done event");
                toolCalls.clear();
                break;
            }
            String tname = String.valueOf(s.get("name"));
            if (tname == null || tname.isBlank() || "null".equals(tname)) {
                pendingErr = new ProviderException("incomplete",
                    "Responses function_call item " + e.getKey() + " ended without name");
                toolCalls.clear();
                break;
            }
            String callId = String.valueOf(s.get("call_id"));
            if (callId.isBlank() || "null".equals(callId)) {
                pendingErr = new ProviderException("incomplete",
                    "Responses function_call item " + e.getKey() + " ended without call_id");
                toolCalls.clear();
                break;
            }
            String args = s.get("arguments").toString();
            if (args.isEmpty()) {
                args = "{}";
            }
            try {
                M.readTree(args);
            } catch (Exception jpe) {
                pendingErr = new ProviderException("incomplete", "Responses function_call item " + e.getKey()
                    + " (name=" + tname + ") ended with invalid arguments: " + jpe.getMessage());
                toolCalls.clear();
                break;
            }
            toolCalls.add(ToolCall.builder().id(callId).name(tname).arguments(args).build());
        }

        if (messageStarted.get()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("content", assistantText.toString());
            payload.put("toolCalls", toolCalls);
            if (thinkingText.length() > 0) {
                payload.put("reasoning", thinkingText.toString());
            }
            if (pendingErr == null && incompleteReason[0] == null && reasoningItemsJson[0] != null) {
                payload.put("reasoningProvider", responseProviderId(requestModel));
                payload.put("reasoningToken", reasoningItemsJson[0]);
            }
            if (pendingErr == null && incompleteReason[0] == null && responseOutputItemsJson[0] != null) {
                payload.put("responseOutputProvider", responseProviderId(requestModel));
                payload.put("responseOutputItems", responseOutputItemsJson[0]);
            }
            if (refusalSeen.get()) {
                payload.put("refusal", true);
            }
            if (pendingErr != null) {
                payload.put("interrupted", true);
                payload.put("error", pendingErr.code);
            }
            if (incompleteReason[0] != null) {
                payload.put("interrupted", true);
                payload.put("incompleteReason", incompleteReason[0]);
            }
            onEvent.accept(StreamEvent.of("message_end", payload));
        }
        if (log.isDebugEnabled()) {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            boolean interrupted = pendingErr != null || incompleteReason[0] != null;
            String stopStr = pendingErr != null ? pendingErr.code
                : incompleteReason[0] != null ? incompleteReason[0] : "stop";
            ProviderStreamLogger.logResponse(log, requestId, name, assistantText.toString(), thinkingText.toString(),
                toolCalls, stopStr, interrupted, durationMs);
        }
        if (pendingErr != null) {
            throw pendingErr.withExchange(exchange);
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
        ChatRequest req = ChatRequest.builder().model(model).messages(messages).temperature(temperature)
            .maxTokens(maxOutputTokens).build();
        return oneShot(req, timeoutMs);
    }

    @Override public OneShotResult oneShot(ChatRequest req, long timeoutMs) {
        Map<String, Object> body = buildBody(req, false);
        Call call = newCall(buildRequest(body, null));
        return runWithTimeout(call, timeoutMs, resp -> {
            JsonNode json = M.readTree(readBoundedSuccessBody(resp));
            JsonNode usage = json.path("usage");
            ProviderTokenUsage tokenUsage = usage.isObject() && !usage.isEmpty()
                ? ProviderUsage.responses(usage) : null;
            if (tokenUsage != null) tokenUsage.record(metrics);
            try { validateOneShotResponse(json); }
            catch (ProviderException failure) { throw failure.withUsage(tokenUsage); }
            // Non-stream Responses: output[] holds message items whose content[] carries output_text parts.
            StringBuilder sb = new StringBuilder();
            JsonNode outText = json.path("output_text");
            if (outText.isTextual()) {
                sb.append(outText.asText("")); // some SDK-shaped responses expose a flattened convenience field
            } else {
                for (JsonNode item : json.path("output")) {
                    if ("message".equals(item.path("type").asText())) {
                        for (JsonNode part : item.path("content")) {
                            if ("output_text".equals(part.path("type").asText())) {
                                sb.append(part.path("text").asText(""));
                            } else if ("refusal".equals(part.path("type").asText())) {
                                // A completed refusal is still a meaningful model response. Returning an empty string
                                // here would make callers treat a safety refusal as a successful blank answer.
                                sb.append(part.path("refusal").asText(""));
                            }
                        }
                    }
                }
            }
            return new OneShotResult(sb.toString(), name,
                req.getModel() == null ? defaultModel : req.getModel(), tokenUsage);
        });
    }

    private static void validateOneShotResponse(JsonNode response) {
        JsonNode error = response.get("error");
        if (error != null && !error.isNull()) {
            throw responseFailure(response, "500", "responses request failed");
        }

        String status = response.path("status").asText("");
        JsonNode incompleteDetails = response.get("incomplete_details");
        boolean hasIncompleteDetails = incompleteDetails != null && !incompleteDetails.isNull();
        if ("incomplete".equals(status) || hasIncompleteDetails) {
            String reason = hasIncompleteDetails ? incompleteDetails.path("reason").asText("response_incomplete")
                : "response_incomplete";
            throw new ProviderException("incomplete", "Responses request ended incomplete: " + reason);
        }
        if ("failed".equals(status)) {
            throw responseFailure(response, "500", "responses request failed");
        }
        if (!"completed".equals(status)) {
            String rendered = status.isEmpty() ? "missing" : status;
            throw new ProviderException("parse", "Responses request has no completed terminal status: " + rendered);
        }
    }

    private Map<String, Object> buildBody(ChatRequest req, boolean stream) {
        String model = req.getModel() == null ? defaultModel : req.getModel();
        ProviderModelCapabilities.Profile capabilities = modelCapabilities.profile(model);
        if (!capabilities.responsesApi()) {
            throw new ProviderException("config", model
                + " is not configured for the Responses API; check the provider profile");
        }
        if (stream && !capabilities.streaming()) {
            throw new ProviderException("config", model
                + " does not support streaming Responses; use oneShot/Batch or select the base non-Pro model");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);

        StringBuilder sys = new StringBuilder();
        List<ChatMessage> rest = new ArrayList<>();
        boolean leadingInstructionPrefix = true;
        for (ChatMessage m : req.getMessages()) {
            if (leadingInstructionPrefix && "system".equals(m.getRole())) {
                if (sys.length() > 0) {
                    sys.append("\n\n");
                }
                if (m.getContent() != null) {
                    sys.append(m.getContent());
                }
            } else {
                leadingInstructionPrefix = false;
                // A later system reminder is turn state, not part of the reusable top-level instruction prefix.
                // Responses accepts system/developer EasyInputMessage roles, so preserve it chronologically.
                rest.add(m);
            }
        }
        boolean explicitCache = opts.promptCacheExplicit() && capabilities.promptCacheExplicit() && sys.length() > 0;
        if (opts.promptCacheExplicit() && !opts.promptCacheKey()) {
            throw new ProviderException("config", "prompt-cache-explicit requires prompt-cache-key=true");
        }
        if (sys.length() > 0 && !explicitCache) {
            body.put("instructions", sys.toString());
        }
        List<Map<String, Object>> apiInput = toApiInput(rest, model);
        if (explicitCache) {
            apiInput.add(0, explicitCachedSystemItem(sys.toString()));
            body.put("prompt_cache_options", Map.of("mode", "explicit"));
        }
        body.put("input", apiInput);
        ProviderCapabilityRequest.applyOutputTokenLimit(body, capabilities, req.getMaxTokens(), opts.defaultMaxTokens());
        body.put("store", false);

        List<Map<String, Object>> requestedTools = req.getTools() == null ? List.of() : req.getTools();
        String nativeWebSearchType =
            ProviderCapabilityRequest.nativeWebSearchType(capabilities, opts.nativeWebSearch(), model, req);
        boolean nativeWebSearch = nativeWebSearchType != null;
        boolean hasClientFunctionTools = requestedTools.stream()
            .anyMatch(tool -> isEffectiveClientFunctionTool(tool, nativeWebSearch));
        ProviderCapabilityRequest.requireFunctionTools(capabilities, hasClientFunctionTools, model);
        List<Map<String, Object>> apiTools = toApiTools(requestedTools, nativeWebSearchType);
        String reasoningEffort = ProviderCapabilityRequest.reasoningEffort(capabilities,
            req.getReasoningEffort() != null ? req.getReasoningEffort() : opts.effort(),
            hasClientFunctionTools, model, "reasoning.effort");
        boolean useReasoning = opts.reasoning() || reasoningEffort != null;
        ProviderCapabilityRequest.requireReasoning(capabilities, useReasoning, model);
        if (useReasoning) {
            Map<String, Object> reasoning = new LinkedHashMap<>();
            reasoning.put("summary", "auto");
            if (reasoningEffort != null) {
                reasoning.put("effort", reasoningEffort);
            }
            body.put("reasoning", reasoning);
        }
        // Omitting the reasoning request object does not disable reasoning-model defaults. In stateless mode, request
        // encrypted continuation state for every explicitly reasoning-capable model so a default reasoning item is
        // never returned without the material required for the next turn.
        if (capabilities.reasoningSupported()) {
            body.put("include", List.of("reasoning.encrypted_content"));
        }
        if (opts.sendTemperature() && req.getTemperature() != null
            && ProviderCapabilityRequest.sendSampling(capabilities, useReasoning)) {
            body.put("temperature", req.getTemperature());
        }
        body.put("stream", stream);
        if (!apiTools.isEmpty()) {
            body.put("tools", apiTools);
        }
        if (opts.promptCacheKey() && capabilities.promptCacheKey()) {
            body.put("prompt_cache_key", PromptCacheKeys.forRequest(name, model, req.getPromptCacheScope(),
                req.getMessages(), apiTools));
        }
        ProviderCapabilityRequest.applyRequestOptions(body, capabilities);
        PromptCachePlan.apply(body, req, capabilities, opts.promptCacheExplicit());
        return body;
    }

    private static Map<String, Object> explicitCachedSystemItem(String systemText) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "input_text");
        block.put("text", systemText);
        block.put("prompt_cache_breakpoint", Map.of("mode", "explicit"));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("role", "system");
        item.put("content", List.of(block));
        return item;
    }

    /**
     * Translate internal {@link ChatMessage}s into Responses {@code input} items. Reasoning blocks captured from a
     * prior turn (provider {@code openai-responses}, token present) are replayed as their original {@code reasoning}
     * items immediately before the matching {@code function_call} items — required for stateless reasoning continuity.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toApiInput(List<ChatMessage> msgs, String model) {
        List<Map<String, Object>> out = new ArrayList<>();
        InlineBudget inlineBudget = new InlineBudget(MAX_INLINE_REQUEST_BYTES, maxImagesPerMessage);
        Map<ChatMessage, Map<String, Object>> preparedUserItems = new IdentityHashMap<>();
        // Allocate the request-wide inline budget to recent turns first, then emit the prepared items in chronological
        // order. Historical attachments retain a visible fallback instead of starving the current user input.
        for (int index = msgs.size() - 1; index >= 0; index--) {
            ChatMessage message = msgs.get(index);
            if (message != null && "user".equals(message.getRole()) && message.getAttachments() != null
                && !message.getAttachments().isEmpty()) {
                preparedUserItems.put(message, messageItem(message, model, inlineBudget));
            }
        }
        boolean nativeReplay = modelCapabilities.profile(model).reasoningReplay()
            == ProviderModelCapabilities.ReasoningReplay.NATIVE_ITEMS;
        List<Object> pendingReasoning = null;
        int i = 0;
        while (i < msgs.size()) {
            ChatMessage m = msgs.get(i);
            if (m.isReasoningBlock()) {
                pendingReasoning = null;
                ChatMessage next = (i + 1 < msgs.size()) ? msgs.get(i + 1) : null;
                // Replay is gated by what the provider actually returned, not by today's request option. GPT-5.x can
                // emit reasoning under a model default / effort-only configuration even when opts.reasoning is false.
                boolean replayable = nativeReplay && m.getReasoningToken() != null && !m.getReasoningToken().isBlank()
                    && responseStateProducedHere(m.getReasoningProvider(), model)
                    && next != null && "assistant".equals(next.getRole()) && next.getToolCalls() != null
                    && !next.getToolCalls().isEmpty();
                if (replayable) {
                    try {
                        pendingReasoning = M.readValue(m.getReasoningToken(), List.class);
                    } catch (Exception ex) {
                        log.warn("Responses: could not parse stored reasoning items for replay: {}", ex.getMessage());
                    }
                }
                i++;
                continue;
            }
            if ("tool".equals(m.getRole())) {
                pendingReasoning = null;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("type", "function_call_output");
                item.put("call_id", m.getToolCallId() == null ? "" : m.getToolCallId());
                item.put("output", m.getContent() == null ? "" : m.getContent());
                out.add(item);
                i++;
                continue;
            }
            if ("assistant".equals(m.getRole())) {
                List<Map<String, Object>> storedOutput = nativeReplay ? storedResponseOutputItems(m, model) : null;
                if (storedOutput != null) {
                    // The raw output already contains reasoning, message/function-call items, ids, status, phase and
                    // caller metadata. Do not prepend pendingReasoning or reconstruct presentation fields: either
                    // would duplicate items and violate the provider's required untouched ordering.
                    out.addAll(storedOutput);
                    pendingReasoning = null;
                    i++;
                    continue;
                }
            }
            if ("assistant".equals(m.getRole()) && m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
                if (pendingReasoning != null) {
                    for (Object r : pendingReasoning) {
                        if (r instanceof Map) {
                            out.add((Map<String, Object>)r);
                        }
                    }
                }
                String text = m.getContent();
                if (text != null && !text.isEmpty()) {
                    out.add(messageItem("assistant", text));
                }
                for (ToolCall tc : m.getToolCalls()) {
                    if (tc.getId() == null || tc.getId().isBlank() || tc.getName() == null || tc.getName().isBlank()) {
                        log.error("OpenAiResponsesProvider: dropping replayed function_call with null/blank id/name "
                            + "(id={}, name={})", tc.getId(), tc.getName());
                        continue;
                    }
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("type", "function_call");
                    item.put("call_id", tc.getId());
                    item.put("name", tc.getName());
                    item.put("arguments",
                        tc.getArguments() == null || tc.getArguments().isBlank() ? "{}" : tc.getArguments());
                    out.add(item);
                }
                pendingReasoning = null;
                i++;
                continue;
            }
            pendingReasoning = null;
            out.add(preparedUserItems.containsKey(m) ? preparedUserItems.get(m) : messageItem(m, model, inlineBudget));
            i++;
        }
        return out;
    }

    /**
     * Parse provider-native output persisted on a prior assistant turn.
     *
     * <p>Null means the opaque state is unavailable, malformed, or no longer matches the effective tool calls; the
     * caller then reconstructs only the validated assistant text/function calls instead of replaying unsafe state.</p>
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> storedResponseOutputItems(ChatMessage message, String model) {
        if (!responseStateProducedHere(message.getResponseOutputProvider(), model)
            || message.getResponseOutputItems() == null || message.getResponseOutputItems().isBlank()) {
            return null;
        }
        try {
            List<Object> parsed = M.readValue(message.getResponseOutputItems(), List.class);
            if (parsed.isEmpty() || parsed.stream().anyMatch(item -> !(item instanceof Map))) {
                log.warn("Responses: stored response output is empty or contains a non-object item; reconstructing");
                return null;
            }
            List<Map<String, Object>> items = new ArrayList<>(parsed.size());
            for (Object item : parsed) {
                items.add((Map<String, Object>)item);
            }
            if (!nativeFunctionCallsMatch(items, message.getToolCalls())) {
                log.warn("Responses: stored native function calls no longer match assistant toolCalls; reconstructing");
                return null;
            }
            return items;
        } catch (Exception ex) {
            log.warn("Responses: could not parse stored response output for replay: {}", ex.getMessage());
            return null;
        }
    }

    private String responseProviderId(String model) {
        return REASONING_PROVIDER + ":" + name + ":" + normaliseModelId(model);
    }

    private boolean responseStateProducedHere(String provenance, String model) {
        // Unscoped tags cannot distinguish two Responses providers with the same default model. Opaque encrypted state
        // is fail-closed: only the current provider+model scoped provenance is replayable.
        return responseProviderId(model).equals(provenance);
    }

    private static boolean nativeFunctionCallsMatch(List<Map<String, Object>> items, List<ToolCall> toolCalls) {
        List<ToolCall> nativeCalls = new ArrayList<>();
        for (Map<String, Object> item : items) {
            if (!"function_call".equals(item.get("type"))) {
                continue;
            }
            nativeCalls.add(ToolCall.builder().id(stringValue(item.get("call_id")))
                .name(stringValue(item.get("name"))).arguments(stringValue(item.get("arguments"))).build());
        }
        List<ToolCall> current = toolCalls == null ? List.of() : toolCalls;
        if (nativeCalls.size() != current.size()) {
            return false;
        }
        for (int i = 0; i < nativeCalls.size(); i++) {
            ToolCall nativeCall = nativeCalls.get(i);
            ToolCall currentCall = current.get(i);
            if (currentCall == null || !Objects.equals(nativeCall.getId(), currentCall.getId())
                || !Objects.equals(nativeCall.getName(), currentCall.getName())) {
                return false;
            }
            try {
                String nativeArgs = nativeCall.getArguments() == null || nativeCall.getArguments().isBlank()
                    ? "{}" : nativeCall.getArguments();
                String currentArgs = currentCall.getArguments() == null || currentCall.getArguments().isBlank()
                    ? "{}" : currentCall.getArguments();
                if (!M.readTree(nativeArgs).equals(M.readTree(currentArgs))) {
                    return false;
                }
            } catch (Exception ex) {
                return false;
            }
        }
        return true;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String normaliseModelId(String model) {
        return model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, Object> messageItem(String role, String text) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("role", role);
        item.put("content", text);
        return item;
    }

    /** Build one Responses message item, using typed user content when attachments are present. */
    private Map<String, Object> messageItem(ChatMessage message, String model, InlineBudget inlineBudget) {
        String text = message.getContent() == null ? "" : message.getContent();
        List<MessageAttachment> attachments = message.getAttachments();
        if (!"user".equals(message.getRole()) || attachments == null || attachments.isEmpty()) {
            return messageItem(message.getRole(), text);
        }

        List<Map<String, Object>> content = new ArrayList<>();
        if (!text.isEmpty()) {
            content.add(Map.of("type", "input_text", "text", text));
        }
        boolean supportsImages = supportsImageInput(model);
        int imageCount = 0;
        for (MessageAttachment attachment : attachments) {
            if (attachment == null) {
                content.add(inputText("[an attachment was not sent inline because its metadata is missing]"));
                continue;
            }
            String name = AttachmentPayloadLoader.displayName(attachment);
            String location = AttachmentPayloadLoader.visiblePath(attachment, uploadsRoot);
            if (!attachment.isImage()) {
                String mime = attachment.getMime() == null ? "" : attachment.getMime().toLowerCase(Locale.ROOT);
                if (!visionEnabled || !supportsFileInput(model) || !SUPPORTED_FILE_MIMES.contains(mime)) {
                    content.add(inputText("[attached file " + name + " is available at " + location
                        + "; it was not sent inline (model/configuration or MIME does not support file input); use "
                        + "file_read to inspect it]"));
                    continue;
                }
                AttachmentPayloadLoader.Loaded loaded = inlineBudget.canAcceptAnother()
                    ? AttachmentPayloadLoader.load(attachment, uploadsRoot,
                        Math.min(maxImageBytes, inlineBudget.remainingBytes()))
                    : null;
                if (loaded == null || !inlineBudget.consume(loaded.bytes().length)) {
                    content.add(inputText("[attached file " + name
                        + " was not sent inline because it is unsafe, unreadable, or exceeds the request limit; use "
                        + "file_read on " + location + "]"));
                    continue;
                }
                Map<String, Object> file = new LinkedHashMap<>();
                file.put("type", "input_file");
                file.put("filename", loaded.name());
                file.put("file_data", "data:" + mime + ";base64,"
                    + Base64.getEncoder().encodeToString(loaded.bytes()));
                content.add(file);
                continue;
            }
            if (!visionEnabled) {
                content.add(inputText("[image " + name
                    + " was not sent inline because vision is disabled; use file_read/OCR on " + location
                    + "]"));
                continue;
            }
            if (!supportsImages) {
                content.add(inputText("[image " + name + " was not sent inline because model " + model
                    + " is not configured with input modality image; use file_read/OCR on " + location
                    + "]"));
                continue;
            }
            String mime = attachment.getMime() == null ? "" : attachment.getMime().toLowerCase(Locale.ROOT);
            if (!SUPPORTED_IMAGE_MIMES.contains(mime)) {
                content.add(inputText("[image " + name + " was not sent inline: unsupported MIME " + mime
                    + "; use file_read/OCR on " + location + "]"));
                continue;
            }
            if (imageCount >= maxImagesPerMessage) {
                content.add(inputText("[image " + name + " omitted: per-message limit " + maxImagesPerMessage
                    + " reached]"));
                continue;
            }
            AttachmentPayloadLoader.Loaded loaded = inlineBudget.canAcceptAnother()
                ? AttachmentPayloadLoader.load(attachment, uploadsRoot,
                    Math.min(maxImageBytes, inlineBudget.remainingBytes()))
                : null;
            if (loaded == null || !inlineBudget.consume(loaded.bytes().length)) {
                content.add(inputText("[image " + name
                    + " skipped: unsafe, unreadable, or over the request limit; use file_read/OCR on "
                    + location + "]"));
                continue;
            }
            Map<String, Object> image = new LinkedHashMap<>();
            image.put("type", "input_image");
            image.put("image_url", "data:" + mime + ";base64,"
                + Base64.getEncoder().encodeToString(loaded.bytes()));
            image.put("detail", visionDetail);
            content.add(image);
            imageCount++;
        }
        if (content.isEmpty()) {
            content.add(inputText(""));
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("role", "user");
        item.put("content", content);
        return item;
    }

    private boolean supportsImageInput(String model) {
        ProviderModelCapabilities.Profile profile = modelCapabilities.profile(model);
        return profile.exactModel() ? profile.imageInput()
            : model != null && imageInputModels.contains(model.trim().toLowerCase(Locale.ROOT));
    }

    private boolean supportsFileInput(String model) {
        ProviderModelCapabilities.Profile profile = modelCapabilities.profile(model);
        return profile.exactModel() ? profile.fileInput()
            : model != null && fileInputModels.contains(model.trim().toLowerCase(Locale.ROOT));
    }

    private static Map<String, Object> inputText(String text) {
        return Map.of("type", "input_text", "text", text == null ? "" : text);
    }

    private static Set<String> normaliseModelIds(Set<String> models) {
        return models == null ? Set.of()
            : models.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    private static final class InlineBudget {
        private long remaining;
        private final int maxCount;
        private int count;

        private InlineBudget(long remaining, int maxCount) {
            this.remaining = remaining;
            this.maxCount = Math.max(0, maxCount);
        }

        private boolean canAcceptAnother() {
            return count < maxCount && remaining > 0;
        }

        private long remainingBytes() {
            return Math.max(0L, remaining);
        }

        private boolean consume(long bytes) {
            if (!canAcceptAnother() || bytes < 0 || bytes > remaining) {
                return false;
            }
            remaining -= bytes;
            count++;
            return true;
        }
    }

    private static String boundedNativeItemsJson(JsonNode items, String label) throws IOException {
        if (!items.isArray() || items.size() > MAX_NATIVE_OUTPUT_ITEMS) {
            throw new ProviderException("agent_oversize",
                "Responses " + label + " exceeded " + MAX_NATIVE_OUTPUT_ITEMS + " items");
        }
        String json = M.writeValueAsString(items);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_NATIVE_OUTPUT_BYTES) {
            throw new ProviderException("agent_oversize",
                "Responses " + label + " exceeded " + MAX_NATIVE_OUTPUT_BYTES + " bytes");
        }
        return json;
    }

    /** Serialise a request body to JSON for DEBUG logging; never throws (falls back to toString on error). */
    private static String toJson(Object body) {
        try {
            return M.writeValueAsString(body);
        } catch (Exception e) {
            return String.valueOf(body);
        }
    }

    /**
     * Translate OpenAI Chat-Completions tool schema ({@code {type:"function", function:{name,description,parameters}}})
     * into the Responses internally-tagged shape ({@code {type:"function", name, description, parameters}}).
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toApiTools(List<Map<String, Object>> tools, String nativeWebSearchType) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : tools) {
            Map<String, Object> fn = (Map<String, Object>)t.getOrDefault("function", t);
            // Per-provider mutual exclusion: drop our own web_search when native is on (native block appended below).
            if (nativeWebSearchType != null && "web_search".equals(fn.get("name"))) {
                continue;
            }
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("type", "function");
            a.put("name", fn.get("name"));
            if (fn.get("description") != null) {
                a.put("description", fn.get("description"));
            }
            Object params = fn.get("parameters");
            if (params == null) {
                params = Map.of("type", "object", "properties", Map.of());
            }
            a.put("parameters", params);
            out.add(a);
        }
        // Native OpenAI Responses web search tool (runs server-side, returns cited results inline — not via our
        // ToolExecutor). The exact type comes from the resolved model capability.
        if (nativeWebSearchType != null) {
            Map<String, Object> nativeTool = new LinkedHashMap<>();
            nativeTool.put("type", nativeWebSearchType);
            out.add(nativeTool);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private boolean isEffectiveClientFunctionTool(Map<String, Object> tool, boolean nativeWebSearch) {
        if (tool == null) {
            return false;
        }
        Map<String, Object> fn = (Map<String, Object>)tool.getOrDefault("function", tool);
        return !(nativeWebSearch && "web_search".equals(fn.get("name")));
    }

    private Request buildRequest(Map<String, Object> body, String idempotencyKey) {
        String json;
        try {
            json = M.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Request.Builder b = new Request.Builder().url(resolveEndpoint(baseUrl, "/responses")).post(RequestBody.create(json, JSON));
        if (apiKey != null && !apiKey.isBlank()) {
            b.addHeader("Authorization", "Bearer " + apiKey);
        }
        if (Boolean.TRUE.equals(body.get("stream"))) {
            b.addHeader("Accept", "text/event-stream");
        }
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            b.addHeader("Idempotency-Key", idempotencyKey);
        }

        // Codex CLI emulation headers
        if (opts.emulation()) {
            applyEmulationHeaders(b);
        }

        // Full (masked) request logging is handled centrally by ProviderStreamLogger.logRequest on the
        // streaming paths — no emulation-specific DEBUG block here, so a request is only logged once and
        // headers (which carry the API key) are never printed.
        return b.build();
    }

    /**
     * Apply Codex CLI emulation headers to the given request builder. Shared between the SSE request path
     * ({@link #buildRequest}) and the WebSocket upgrade path ({@link #streamChatViaWebSocket}).
     */
    private void applyEmulationHeaders(Request.Builder b) {
        String ver = opts.codexVersion();
        // P0: Core identity headers (relay key checkpoints)
        b.removeHeader("User-Agent");
        b.addHeader("User-Agent", "codex_cli_rs/" + ver + " (Windows_NT 10.0.22621; x86_64) WindowsTerminal/1.21");
        b.addHeader("Originator", "codex_cli_rs");
        b.addHeader("Version", ver);
        b.addHeader("Cli_version", ver);
        // P1: Session & request identity
        b.addHeader("Session_id", sessionId);
        b.addHeader("X-Client-Request-Id", UUID.randomUUID().toString());
        // P0: OpenAI Beta flag (critical - relay key checkpoint)
        b.addHeader("OpenAI-Beta", "responses_websockets=2026-02-06");
        // P1: Internal Codex headers
        b.addHeader("x-openai-internal-codex-residency", "us");
        // P1: Codex-specific headers
        b.addHeader("x-codex-installation-id", sessionId);
        b.addHeader("x-codex-protocol-version", "9");
        b.addHeader("x-codex-window-id", UUID.randomUUID().toString());
        b.addHeader("x-responsesapi-include-timing-metrics", "true");
        // P1: Stainless SDK identity
        b.addHeader("X-Stainless-Lang", "js");
        b.addHeader("X-Stainless-OS", "Windows");
        b.addHeader("X-Stainless-Arch", "x64");
        b.addHeader("X-Stainless-Runtime", "node");
        b.addHeader("X-Stainless-Runtime-Version", "v22.14.0");
        b.addHeader("X-Stainless-Package-Version", ver);
        b.addHeader("X-Stainless-Retry-Count", "0");
        // P2: W3C trace context (real Codex sends these)
        String traceId = UUID.randomUUID().toString().replace("-", "");
        String spanId = traceId.substring(0, 16);
        b.addHeader("traceparent", "00-" + traceId + "-" + spanId + "-01");
    }
}
