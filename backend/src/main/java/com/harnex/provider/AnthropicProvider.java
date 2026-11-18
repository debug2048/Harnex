package com.harnex.provider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harnex.observability.HarnexMetrics;
import com.harnex.model.ChatMessage;
import com.harnex.model.MessageAttachment;
import com.harnex.model.StreamEvent;
import com.harnex.model.ToolCall;
import com.harnex.net.ProxyResolution;
import com.harnex.util.Ids;

import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * Native Anthropic Messages API provider (https://api.anthropic.com/v1/messages).
 *
 * <p>
 * Differences from the OpenAI-compatible shape that justify a dedicated adapter:
 * <ul>
 * <li>{@code system} prompt lives at the request top level, not as a role=system message.</li>
 * <li>Content blocks are typed objects ({@code text}, {@code tool_use}, {@code tool_result}, {@code thinking}).</li>
 * <li>Streaming uses named SSE events ({@code message_start}, {@code content_block_delta}, {@code message_delta},
 * {@code message_stop}, {@code ping}) with the event payload's {@code type} field as the authority.</li>
 * <li>Tool-call argument streaming arrives as {@code input_json_delta.partial_json} chunks.</li>
 * <li>{@code max_tokens} is REQUIRED.</li>
 * </ul>
 *
 * <p>
 * The wire version header ({@code anthropic-version: 2023-06-01}) is the current GA value the official SDKs send and is
 * deliberately NOT bumped. Modern-model behaviour (adaptive thinking, effort, prompt caching, large output) is
 * controlled by request-body fields + headers via {@link Options}, not by the version header.
 *
 * <p>
 * This adapter translates BOTH directions so the rest of the harness keeps speaking the internal OpenAI-ish vocabulary
 * ({@link ChatMessage}/{@link ToolCall}). Extended-thinking blocks are captured with their {@code signature} and
 * replayed before the tool call on the next turn (Anthropic requires this when thinking is enabled together with tool
 * use); see {@link #toApiMessages}.
 */
@Slf4j
public class AnthropicProvider extends AbstractHttpProvider {

    private static final String API_VERSION = "2023-06-01";
    private static final String OUTPUT_PROVIDER = "anthropic";
    private static final String CONTINUATION_BOUNDARY_TYPE = "harnex_anthropic_message_boundary";
    private static final int CONTINUATION_BOUNDARY_BYTES =
        ("{\"type\":\"" + CONTINUATION_BOUNDARY_TYPE + "\"}").getBytes(StandardCharsets.UTF_8).length;
    private static final Set<String> REPLAYABLE_CONTENT_BLOCK_TYPES =
        Set.of("text", "thinking", "redacted_thinking", "tool_use", "server_tool_use", "compaction");
    private static final Set<String> SUPPORTED_IMAGE_MIMES =
        Set.of("image/jpeg", "image/png", "image/gif", "image/webp");
    private static final String PDF_MIME = "application/pdf";
    private static final String REFUSAL_FALLBACK = "The model declined to answer this request.";
    private static final int MAX_NATIVE_OUTPUT_ITEMS = 256;
    private static final int MAX_NATIVE_OUTPUT_BYTES = 8 * 1024 * 1024;
    private static final int MAX_PAUSE_CONTINUATIONS = 4;
    private static final String COMPACTION_BETA = "compact-2026-09-04";
    private final Map<String, Boolean> compactionCapabilities = new java.util.concurrent.ConcurrentHashMap<>();
    // CC emulation constants
    private static final String CC_VERSION_DEFAULT = "2.1.187";
    private static final String CC_SALT = "harnex";
    private static final List<String> CC_BETAS = List.of("claude-code-20250219", "prompt-caching-scope-2026-01-05",
        "advanced-tool-use-2025-11-20");

    private final Options opts;
    private final String replayOwner;
    private final ProviderModelCapabilities modelCapabilities;
    private final boolean visionEnabled;
    /** Exact endpoint model ids explicitly configured for each native attachment modality. */
    private final Set<String> imageInputModels;
    private final Set<String> pdfInputModels;
    private final Path uploadsRoot;
    private final long maxAttachmentBytes;
    private final long maxRequestAttachmentBytes;
    private final int maxAttachmentsPerRequest;
    /** Stable per-provider session ID used for CC emulation. */
    private final String sessionId = UUID.randomUUID().toString();

    /**
     * Per-provider request-shape configuration, assembled from {@link ResolvedProvider} by
     * {@link ProviderRouter}. The required output limit is resolved from the model/protocol profile unless overridden.
     */
    public record Options(int defaultMaxTokens, String thinking, String effort, boolean promptCaching,
        List<String> betas, boolean sendTemperature, boolean nativeWebSearch, boolean emulation,
        String emulationVersion) {
        public static Options defaults() {
            return new Options(0, null, null, true, List.of(), false);
        }

        public String ccVersion() {
            return (emulationVersion == null || emulationVersion.isBlank()) ? CC_VERSION_DEFAULT : emulationVersion;
        }

        public Options(int defaultMaxTokens, String thinking, String effort, boolean promptCaching, List<String> betas,
            boolean sendTemperature) {
            this(defaultMaxTokens, thinking, effort, promptCaching, betas, sendTemperature, false, false, null);
        }

        public Options(int defaultMaxTokens, String thinking, String effort, boolean promptCaching, List<String> betas,
            boolean sendTemperature, boolean nativeWebSearch) {
            this(defaultMaxTokens, thinking, effort, promptCaching, betas, sendTemperature, nativeWebSearch, false,
                null);
        }
    }

    public AnthropicProvider(String name, String baseUrl, String apiKey, String defaultModel, HarnexMetrics metrics) {
        this(name, baseUrl, apiKey, defaultModel, metrics, null, Options.defaults());
    }

    public AnthropicProvider(String name, String baseUrl, String apiKey, String defaultModel, HarnexMetrics metrics,
        ProxyResolution.Resolved proxy) {
        this(name, baseUrl, apiKey, defaultModel, metrics, proxy, Options.defaults());
    }

    public AnthropicProvider(String name, String baseUrl, String apiKey, String defaultModel, HarnexMetrics metrics,
        ProxyResolution.Resolved proxy, Options opts) {
        this(name, baseUrl, apiKey, defaultModel, metrics, proxy, opts, false, Set.of(), Set.of(), null, 0L, 0L, 0,
            ProviderModelCapabilities.conservative("anthropic"));
    }

    public AnthropicProvider(String name, String baseUrl, String apiKey, String defaultModel, HarnexMetrics metrics,
        ProxyResolution.Resolved proxy, Options opts, ProviderModelCapabilities modelCapabilities) {
        this(name, baseUrl, apiKey, defaultModel, metrics, proxy, opts, false, Set.of(), Set.of(), null, 0L, 0L, 0,
            modelCapabilities);
    }

    /**
     * Attachment-aware constructor. Native bytes are emitted only for model ids present in the corresponding exact
     * set; callers should derive these sets from the configured {@code input-modalities} contract for this endpoint.
     * The historical constructors remain text-only and therefore cannot accidentally enable attachments on a relay.
     */
    public AnthropicProvider(String name, String baseUrl, String apiKey, String defaultModel, HarnexMetrics metrics,
        ProxyResolution.Resolved proxy, Options opts, Set<String> imageInputModels, Set<String> pdfInputModels,
        Path uploadsRoot, long maxAttachmentBytes, long maxRequestAttachmentBytes, int maxAttachmentsPerRequest) {
        this(name, baseUrl, apiKey, defaultModel, metrics, proxy, opts, true, imageInputModels, pdfInputModels,
            uploadsRoot,
            maxAttachmentBytes, maxRequestAttachmentBytes, maxAttachmentsPerRequest,
            ProviderModelCapabilities.conservative("anthropic"));
    }

    /** Back-compatible full constructor; attachment-aware callers historically implied that vision was enabled. */
    public AnthropicProvider(String name, String baseUrl, String apiKey, String defaultModel, HarnexMetrics metrics,
        ProxyResolution.Resolved proxy, Options opts, Set<String> imageInputModels, Set<String> pdfInputModels,
        Path uploadsRoot, long maxAttachmentBytes, long maxRequestAttachmentBytes, int maxAttachmentsPerRequest,
        ProviderModelCapabilities modelCapabilities) {
        this(name, baseUrl, apiKey, defaultModel, metrics, proxy, opts, true, imageInputModels, pdfInputModels,
            uploadsRoot, maxAttachmentBytes, maxRequestAttachmentBytes, maxAttachmentsPerRequest, modelCapabilities);
    }

    /** Full constructor used by {@link ProviderRouter}; capabilities are resolved once at startup. */
    public AnthropicProvider(String name, String baseUrl, String apiKey, String defaultModel, HarnexMetrics metrics,
        ProxyResolution.Resolved proxy, Options opts, boolean visionEnabled, Set<String> imageInputModels,
        Set<String> pdfInputModels, Path uploadsRoot, long maxAttachmentBytes, long maxRequestAttachmentBytes,
        int maxAttachmentsPerRequest, ProviderModelCapabilities modelCapabilities) {
        super(name, (baseUrl == null || baseUrl.isBlank()) ? "https://api.anthropic.com" : baseUrl, apiKey,
            defaultModel, metrics, proxy);
        this.opts = opts == null ? Options.defaults() : opts;
        this.replayOwner = replayOwner(resolveEndpoint(this.baseUrl, "/messages"), apiKey);
        this.modelCapabilities = modelCapabilities == null
            ? ProviderModelCapabilities.conservative("anthropic") : modelCapabilities;
        this.visionEnabled = visionEnabled;
        this.imageInputModels = normaliseModelIds(imageInputModels);
        this.pdfInputModels = normaliseModelIds(pdfInputModels);
        this.uploadsRoot = uploadsRoot == null ? null : uploadsRoot.toAbsolutePath().normalize();
        this.maxAttachmentBytes = Math.max(0L, maxAttachmentBytes);
        this.maxRequestAttachmentBytes = Math.max(0L, maxRequestAttachmentBytes);
        this.maxAttachmentsPerRequest = Math.max(0, maxAttachmentsPerRequest);
    }

    @Override
    public void streamChat(ChatRequest req, Consumer<StreamEvent> onEvent) {
        streamChat(req, onEvent, 0L);
    }

    @Override
    public void streamChat(ChatRequest req, Consumer<StreamEvent> onEvent, long ttftBudgetMs) {
        Map<String, Object> body = buildBody(req, true);
        streamChat(req, onEvent, ttftBudgetMs, body, 0, new ContinuationState());
    }

    /**
     * Execute one Messages stream and, when Anthropic pauses its server-tool loop, resend the complete assistant
     * content as the next assistant message. Continuations are deliberately request-local and bounded.
     */
    private void streamChat(ChatRequest req, Consumer<StreamEvent> onEvent, long ttftBudgetMs,
        Map<String, Object> body, int continuationCount, ContinuationState continuation) {
        Request httpReq = buildRequest(body, continuationIdempotencyKey(req.getIdempotencyKey(), continuationCount));
        String requestModel = req.getModel() == null ? defaultModel : req.getModel();
        // Current Claude 4+/5 families can decide to refuse only at the terminal message_delta. Buffer all visible
        // output until then so a refusal never commits the partial answer/thinking that preceded it. Unknown/legacy
        // endpoints retain immediate streaming for compatibility unless a server tool is present. Server-tool turns
        // must also remain buffered because a pause response is continuation state, not user-visible final output.
        boolean bufferVisibleUntilTerminal =
            modelCapabilities.profile(requestModel).strictTerminal() || hasServerTool(body);

        // DEBUG-only full request/response logging, correlated by requestId (masked, zero-cost at INFO+).
        String requestId = ProviderStreamLogger.newRequestId();
        long startNanos = System.nanoTime();
        if (log.isDebugEnabled()) {
            ProviderStreamLogger.logRequest(log, requestId, name, httpReq.url().toString(), toJson(body));
        }

        StringBuilder assistantText = new StringBuilder();
        StringBuilder thinkingText = new StringBuilder();
        String[] thinkingSignature = {null};
        // index -> partial tool_use block (id, name, args StringBuilder)
        Map<Integer, Map<String, Object>> partialTools = new TreeMap<>();
        Map<Integer, String> contentBlockTypes = new HashMap<>();
        // Complete provider-native assistant content, keyed by Anthropic's block index. Text/thinking fields use
        // separate builders to avoid quadratic copying while deltas stream; they are materialised only at the tail.
        Map<Integer, ObjectNode> nativeContentBlocks = new TreeMap<>();
        Map<Integer, StringBuilder> nativeText = new HashMap<>();
        Map<Integer, StringBuilder> nativeThinking = new HashMap<>();
        Map<Integer, StringBuilder> nativeServerInputs = new HashMap<>();
        Set<Integer> openContentBlocks = new HashSet<>();
        AtomicBoolean nativeReplaySafe = new AtomicBoolean(true);
        AtomicLong promptTokens = new AtomicLong();
        AtomicLong completionTokens = new AtomicLong();
        AtomicLong citationBytes = new AtomicLong();
        ProviderTokenUsage[] usageDetail = {null};
        String[] stopReason = {null};
        String[] stopDetails = {null};
        AtomicBoolean messageStarted = new AtomicBoolean(false);
        AtomicBoolean messageStopSeen = new AtomicBoolean(false);

        Call call = newCall(httpReq);
        AtomicBoolean firstContentSeen = new AtomicBoolean(false);
        long ttftMs = ttftBudgetMs > 0 ? Math.min(ttftBudgetMs, STREAM_TTFT_MS) : STREAM_TTFT_MS;

        ProviderException pendingErr = readSseStream(call, ttftMs, firstContentSeen, "network", () -> {
            messageStarted.set(true);
            if (continuationCount == 0) {
                onEvent.accept(StreamEvent.of("message_start",
                    Map.of("provider", name, "model", requestModel)));
            }
        }, payload -> {
            JsonNode node = M.readTree(payload);
            String type = node.path("type").asText("");
            switch (type) {
                case "message_start" -> {
                    JsonNode message = node.path("message");
                    JsonNode usage = message.path("usage");
                    // input_tokens arrives upfront; cache tokens are billed input too — count them or caching
                    // undercounts.
                    ProviderTokenUsage detail = ProviderUsage.anthropic(usage);
                    usageDetail[0] = detail;
                    promptTokens.set(detail.prompt());
                    String initialStopReason = message.path("stop_reason").asText("");
                    if (!initialStopReason.isEmpty()) {
                        stopReason[0] = initialStopReason;
                    }
                    JsonNode initialStopDetails = message.get("stop_details");
                    if (initialStopDetails != null && !initialStopDetails.isNull()) {
                        stopDetails[0] = initialStopDetails.toString();
                    }
                }
                case "content_block_start" -> {
                    JsonNode cb = node.path("content_block");
                    int idx = node.path("index").asInt(0);
                    String blockType = cb.path("type").asText("");
                    if (idx < 0 || idx > 255 || nativeContentBlocks.size() >= 256
                        && !nativeContentBlocks.containsKey(idx)) {
                        throw new ProviderException("agent_oversize",
                            "content block index out of range (idx=" + idx + ", slots=" + nativeContentBlocks.size()
                                + ")");
                    }
                    if (!cb.isObject() || nativeContentBlocks.containsKey(idx)) {
                        throw new ProviderException("parse", "invalid or duplicate Anthropic content block " + idx);
                    }
                    contentBlockTypes.put(idx, blockType);
                    nativeContentBlocks.put(idx, ((ObjectNode)cb).deepCopy());
                    openContentBlocks.add(idx);
                    // A typed block start proves the model is producing output even when its first field is empty
                    // (notably omitted thinking). Do not let the TTFT watchdog cancel an active response.
                    firstContentSeen.set(true);
                    if (!isReplayableContentBlockType(blockType)) {
                        // Server tools and future block types need their own continuation state machine. Keep their
                        // existing live-stream behaviour, but do not persist a knowingly partial native turn.
                        nativeReplaySafe.set(false);
                    }
                    if ("text".equals(blockType)) {
                        String initial = cb.path("text").asText("");
                        JsonNode initialCitations = cb.get("citations");
                        if (initialCitations != null && initialCitations.isArray()) {
                            long initialCitationBytes =
                                initialCitations.toString().getBytes(StandardCharsets.UTF_8).length;
                            if (initialCitations.size() > MAX_NATIVE_OUTPUT_ITEMS
                                || initialCitationBytes > MAX_NATIVE_OUTPUT_BYTES - citationBytes.get()) {
                                throw new ProviderException("agent_oversize",
                                    "Anthropic citations exceeded the native replay envelope");
                            }
                            citationBytes.addAndGet(initialCitationBytes);
                        }
                        nativeText.put(idx, new StringBuilder(initial));
                        if (!initial.isEmpty()) {
                            if (assistantText.length() + initial.length() > MAX_ASSISTANT_CHARS) {
                                throw new ProviderException("agent_oversize",
                                    "response exceeded " + MAX_ASSISTANT_CHARS + " chars");
                            }
                            assistantText.append(initial);
                            if (!bufferVisibleUntilTerminal) {
                                onEvent.accept(StreamEvent.of("text_delta", Map.of("text", initial)));
                            }
                        }
                    } else if ("thinking".equals(blockType)) {
                        String initial = cb.path("thinking").asText("");
                        if (initial.length() > MAX_REASONING_CHARS) {
                            throw new ProviderException("agent_oversize",
                                "reasoning response exceeded " + MAX_REASONING_CHARS + " chars");
                        }
                        nativeThinking.put(idx, new StringBuilder(initial));
                        if (!initial.isEmpty()) {
                            firstContentSeen.set(true);
                            appendBounded(thinkingText, initial, MAX_REASONING_CHARS, "reasoning response");
                            if (!bufferVisibleUntilTerminal) {
                                onEvent.accept(StreamEvent.of("thinking_delta", Map.of("text", initial)));
                            }
                        }
                        String initialSignature = cb.path("signature").asText("");
                        if (!initialSignature.isEmpty()) {
                            thinkingSignature[0] = initialSignature;
                        }
                    }
                    if ("tool_use".equals(blockType)) {
                        Map<String, Object> slot = partialTools.computeIfAbsent(idx, k -> {
                            Map<String, Object> s = new LinkedHashMap<>();
                            s.put("arguments", new StringBuilder());
                            return s;
                        });
                        slot.put("id", cb.path("id").asText("call_" + idx));
                        slot.put("name", cb.path("name").asText(""));
                        firstContentSeen.set(true);
                    } else if ("server_tool_use".equals(blockType)) {
                        // Hosted/server tools (Anthropic web_search) execute inside Anthropic and are not local
                        // tool calls. Their input streams with the same partial-JSON shape as a client tool and must be
                        // materialised before a pause response can be returned verbatim.
                        JsonNode initialInput = cb.get("input");
                        nativeServerInputs.put(idx, new StringBuilder(
                            initialInput != null && initialInput.isObject() && initialInput.size() > 0
                                ? initialInput.toString() : ""));
                        firstContentSeen.set(true);
                    } else if (isServerToolResultType(blockType)) {
                        // Server result blocks arrive complete in content_block_start and carry encrypted continuation
                        // state. The deep copy above is therefore already the exact block to replay.
                        firstContentSeen.set(true);
                    }
                }
                case "content_block_delta" -> {
                    JsonNode delta = node.path("delta");
                    String dType = delta.path("type").asText("");
                    if ("text_delta".equals(dType)) {
                        String t = delta.path("text").asText("");
                        if (!t.isEmpty()) {
                            firstContentSeen.set(true);
                            if (assistantText.length() + t.length() > MAX_ASSISTANT_CHARS) {
                                throw new ProviderException("agent_oversize",
                                    "response exceeded " + MAX_ASSISTANT_CHARS + " chars");
                            }
                            assistantText.append(t);
                            StringBuilder rawText = nativeText.get(node.path("index").asInt(0));
                            if (rawText == null) {
                                nativeReplaySafe.set(false);
                            } else {
                                rawText.append(t);
                            }
                            if (!bufferVisibleUntilTerminal) {
                                onEvent.accept(StreamEvent.of("text_delta", Map.of("text", t)));
                            }
                        }
                    } else if ("input_json_delta".equals(dType)) {
                        int idx = node.path("index").asInt(0);
                        String contentType = contentBlockTypes.get(idx);
                        if ("server_tool_use".equals(contentType)) {
                            String chunk = delta.path("partial_json").asText("");
                            if (!chunk.isEmpty()) {
                                firstContentSeen.set(true);
                            }
                            StringBuilder input = nativeServerInputs.computeIfAbsent(idx, ignored -> new StringBuilder());
                            if (input.length() + chunk.length() > MAX_TOOL_ARGS_CHARS) {
                                throw new ProviderException("agent_oversize",
                                    "server_tool_use input exceeded " + MAX_TOOL_ARGS_CHARS + " chars");
                            }
                            input.append(chunk);
                            break;
                        }
                        if (!"tool_use".equals(contentType)) {
                            nativeReplaySafe.set(false);
                            break;
                        }
                        if (idx < 0 || idx > 255 || partialTools.size() > 256) {
                            throw new ProviderException("agent_oversize",
                                "tool_use input_json_delta index out of range (idx=" + idx + ", slots="
                                    + partialTools.size() + ")");
                        }
                        // Pre-create a placeholder if content_block_start was out-of-order/dropped so the
                        // partial_json isn't silently lost; id/name are filled by the late start (or the call is
                        // dropped at assembly).
                        Map<String, Object> slot = partialTools.computeIfAbsent(idx, k -> {
                            Map<String, Object> s = new LinkedHashMap<>();
                            s.put("id", "call_" + k);
                            s.put("name", "");
                            s.put("arguments", new StringBuilder());
                            return s;
                        });
                        StringBuilder sb = (StringBuilder)slot.get("arguments");
                        String chunk = delta.path("partial_json").asText("");
                        if (sb.length() + chunk.length() > MAX_TOOL_ARGS_CHARS) {
                            partialTools.clear();
                            throw new ProviderException("agent_oversize",
                                "tool_use input exceeded " + MAX_TOOL_ARGS_CHARS + " chars");
                        }
                        if (!chunk.isEmpty()) {
                            firstContentSeen.set(true);
                        }
                        sb.append(chunk);
                    } else if ("thinking_delta".equals(dType)) {
                        String t = delta.path("thinking").asText("");
                        if (!t.isEmpty()) {
                            firstContentSeen.set(true);
                            appendBounded(thinkingText, t, MAX_REASONING_CHARS, "reasoning response");
                            StringBuilder rawThinking = nativeThinking.get(node.path("index").asInt(0));
                            if (rawThinking == null) {
                                nativeReplaySafe.set(false);
                            } else {
                                appendBounded(rawThinking, t, MAX_REASONING_CHARS, "native reasoning response");
                            }
                            if (!bufferVisibleUntilTerminal) {
                                onEvent.accept(StreamEvent.of("thinking_delta", Map.of("text", t)));
                            }
                        }
                    } else if ("signature_delta".equals(dType)) {
                        // Extended-thinking blocks carry a cryptographic signature that must be replayed
                        // verbatim alongside the thinking text on the next turn (see toApiMessages).
                        String sig = delta.path("signature").asText("");
                        if (!sig.isEmpty()) {
                            firstContentSeen.set(true);
                            thinkingSignature[0] = sig;
                            ObjectNode rawBlock = nativeContentBlocks.get(node.path("index").asInt(0));
                            if (rawBlock == null || !"thinking".equals(rawBlock.path("type").asText())) {
                                nativeReplaySafe.set(false);
                            } else {
                                rawBlock.put("signature", sig);
                            }
                        }
                    } else if ("citations_delta".equals(dType)) {
                        int idx = node.path("index").asInt(0);
                        ObjectNode rawBlock = nativeContentBlocks.get(idx);
                        JsonNode citation = delta.get("citation");
                        if (rawBlock == null || !"text".equals(rawBlock.path("type").asText())
                            || citation == null || !citation.isObject()) {
                            nativeReplaySafe.set(false);
                        } else {
                            JsonNode existing = rawBlock.get("citations");
                            ArrayNode citations;
                            if (existing == null || existing.isNull()) {
                                citations = rawBlock.putArray("citations");
                            } else if (existing.isArray()) {
                                citations = (ArrayNode)existing;
                            } else {
                                nativeReplaySafe.set(false);
                                break;
                            }
                            long additionBytes = citation.toString().getBytes(StandardCharsets.UTF_8).length;
                            if (citations.size() >= MAX_NATIVE_OUTPUT_ITEMS
                                || additionBytes > MAX_NATIVE_OUTPUT_BYTES - citationBytes.get()) {
                                throw new ProviderException("agent_oversize",
                                    "Anthropic citations exceeded the native replay envelope");
                            }
                            citationBytes.addAndGet(additionBytes);
                            citations.add(citation.deepCopy());
                            firstContentSeen.set(true);
                        }
                    } else {
                        // Unknown deltas remain forward-compatible for live display, but cannot be replayed exactly.
                        nativeReplaySafe.set(false);
                    }
                }
                case "content_block_stop" -> openContentBlocks.remove(node.path("index").asInt(0));
                case "message_delta" -> {
                    JsonNode usage = node.path("usage");
                    if (usage.isObject()) {
                        long output = usage.path("output_tokens").asLong(completionTokens.get());
                        completionTokens.set(output);
                        ProviderTokenUsage prior = usageDetail[0];
                        long reasoning = usage.path("output_tokens_details").path("thinking_tokens")
                            .asLong(prior == null ? 0 : prior.reasoning());
                        usageDetail[0] = new ProviderTokenUsage(promptTokens.get(), output,
                            prior == null ? 0 : prior.cachedInput(), prior == null ? 0 : prior.cacheWriteInput(),
                            reasoning);
                    }
                    String sr = node.path("delta").path("stop_reason").asText("");
                    if (!sr.isEmpty()) {
                        stopReason[0] = sr;
                    }
                    JsonNode details = node.path("delta").get("stop_details");
                    if (details != null && !details.isNull()) {
                        stopDetails[0] = details.toString();
                    }
                }
                case "message_stop" -> messageStopSeen.set(true);
                case "error" -> {
                    String emsg = node.path("error").path("message").asText("anthropic stream error");
                    throw new ProviderException(anthropicErrorCode(node.path("error").path("type").asText("")), emsg);
                }
                default -> {
                    /* ping / future event types: ignore */ }
            }
            return messageStopSeen.get();
        });

        if (pendingErr == null && !messageStopSeen.get()) {
            pendingErr = new ProviderException("network", "Anthropic stream ended before message_stop");
        }
        if (pendingErr == null && !openContentBlocks.isEmpty()) {
            pendingErr = new ProviderException("parse",
                "Anthropic message_stop arrived with unfinished content blocks " + openContentBlocks);
        }
        if (pendingErr == null && !"pause_turn".equals(stopReason[0])) {
            pendingErr = terminalFailure(stopReason[0], stopDetails[0], true);
        }

        // Emit usage once at the tail (Anthropic sends input_tokens upfront, output_tokens on message_delta).
        long pt = promptTokens.get();
        long ct = completionTokens.get();
        if (pt > 0 || ct > 0) {
            ProviderTokenUsage prior = usageDetail[0];
            ProviderTokenUsage detail = new ProviderTokenUsage(pt, ct, prior == null ? 0 : prior.cachedInput(),
                prior == null ? 0 : prior.cacheWriteInput(), prior == null ? 0 : prior.reasoning());
            Map<String, Object> u = new LinkedHashMap<>(detail.eventData());
            u.put("model", req.getModel() == null ? defaultModel : req.getModel());
            u.put("provider", name);
            onEvent.accept(StreamEvent.of("usage", u));
            detail.record(metrics);
        }

        // Parse every client tool block, but expose calls to AgentLoop ONLY when Anthropic explicitly ended with
        // stop_reason=tool_use. A complete-looking tool block under max_tokens/refusal/pause_turn/context exhaustion is
        // not authorisation to execute side effects.
        List<ToolCall> parsedToolCalls = new ArrayList<>();
        Map<Integer, JsonNode> parsedToolInputs = new HashMap<>();
        boolean invalidToolBlock = false;
        for (Map.Entry<Integer, Map<String, Object>> e : partialTools.entrySet()) {
            Map<String, Object> s = e.getValue();
            String tid = String.valueOf(s.get("id"));
            String tname = String.valueOf(s.get("name"));
            if (tid == null || tid.isBlank() || "null".equals(tid) || tname == null || tname.isBlank()
                || "null".equals(tname)) {
                String argsPeek = peek(s.get("arguments"));
                log.warn("Anthropic stream produced tool_use with no id/name (idx={}, id={}, args=`{}`) — dropping",
                    e.getKey(), s.get("id"), argsPeek);
                invalidToolBlock = true;
                continue;
            }
            String args = s.get("arguments").toString();
            try {
                JsonNode input;
                if (args.isEmpty()) {
                    ObjectNode nativeToolBlock = nativeContentBlocks.get(e.getKey());
                    JsonNode initialInput = nativeToolBlock == null ? null : nativeToolBlock.get("input");
                    input = initialInput != null && initialInput.isObject() ? initialInput.deepCopy()
                        : M.createObjectNode();
                    args = M.writeValueAsString(input);
                } else {
                    input = M.readTree(args);
                }
                if (!input.isObject()) {
                    throw new IllegalArgumentException("tool input must be a JSON object");
                }
                parsedToolInputs.put(e.getKey(), input);
                parsedToolCalls.add(ToolCall.builder().id(tid).name(tname).arguments(args).build());
            } catch (Exception jpe) {
                log.warn("Anthropic dropping tool_use {} (name={}): invalid input JSON: {}", e.getKey(), tname,
                    jpe.getMessage());
                invalidToolBlock = true;
            }
        }

        boolean toolUseTerminal = "tool_use".equals(stopReason[0]);
        if (pendingErr == null && toolUseTerminal && (partialTools.isEmpty() || invalidToolBlock
            || parsedToolCalls.size() != partialTools.size())) {
            pendingErr = new ProviderException("parse",
                "Anthropic stop_reason=tool_use without a complete valid client tool_use block");
        }
        if (pendingErr == null && toolUseTerminal && !nativeReplaySafe.get()) {
            pendingErr = new ProviderException("incomplete",
                "Anthropic mixed client tool_use with content blocks this adapter cannot continue exactly");
        }
        if (pendingErr == null && "pause_turn".equals(stopReason[0]) && !partialTools.isEmpty()) {
            pendingErr = new ProviderException("pause_turn",
                "Anthropic pause_turn unexpectedly contained a client tool_use block");
        }
        if (pendingErr == null && ("end_turn".equals(stopReason[0]) || "stop_sequence".equals(stopReason[0]))
            && !partialTools.isEmpty()) {
            pendingErr = new ProviderException("parse",
                "Anthropic emitted client tool_use blocks but stopped with " + stopReason[0]);
        }
        List<ToolCall> toolCalls = pendingErr == null && toolUseTerminal ? parsedToolCalls : List.of();

        String nativeContentJson = null;
        boolean refusalContainsDiscardedTools = "refusal".equals(stopReason[0]) && !partialTools.isEmpty();
        if (pendingErr == null && nativeReplaySafe.get() && !nativeContentBlocks.isEmpty()
            && !refusalContainsDiscardedTools) {
            nativeContentJson = serializeNativeContent(nativeContentBlocks, nativeText, nativeThinking, parsedToolInputs,
                nativeServerInputs);
        }
        if (pendingErr == null && toolUseTerminal && nativeContentJson == null) {
            pendingErr = new ProviderException("incomplete",
                "Anthropic client tool_use could not be preserved as complete native assistant content");
            toolCalls = List.of();
        }

        if (pendingErr == null && "pause_turn".equals(stopReason[0])) {
            if (nativeContentJson == null) {
                pendingErr = new ProviderException("parse",
                    "Anthropic pause_turn did not contain complete replayable assistant content");
            } else if (continuationCount >= MAX_PAUSE_CONTINUATIONS) {
                pendingErr = new ProviderException("pause_turn_limit",
                    "Anthropic remained paused after " + MAX_PAUSE_CONTINUATIONS + " continuation requests");
            } else {
                Map<String, Object> continuationBody = null;
                try {
                    continuation.add(tagNativeContent(nativeContentJson, body), nativeContentBlocks.size() + 1, assistantText.toString(),
                        thinkingText.toString());
                    continuationBody = continuationBody(body, nativeContentJson);
                } catch (ProviderException pe) {
                    pendingErr = pe;
                }
                if (pendingErr == null) {
                    if (log.isDebugEnabled()) {
                        long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
                        ProviderStreamLogger.logResponse(log, requestId, name, "", "", List.of(), "pause_turn", false,
                            durationMs);
                    }
                    streamChat(req, onEvent, ttftBudgetMs, continuationBody, continuationCount + 1, continuation);
                    return;
                }
            }
        }

        if (pendingErr == null && continuation.hasSegments()) {
            try {
                continuation.requireFinal(nativeContentJson, nativeContentBlocks.size(), assistantText.length(),
                    thinkingText.length());
                assistantText.insert(0, continuation.visibleText());
                thinkingText.insert(0, continuation.thinkingText());
                nativeContentJson = combineNativeContent(continuation.nativeSegments(), tagNativeContent(nativeContentJson, body));
                if (nativeContentJson == null) {
                    throw new ProviderException("parse",
                        "Anthropic continued output could not be represented as complete native content");
                }
            } catch (ProviderException pe) {
                pendingErr = pe;
            }
        }

        if (bufferVisibleUntilTerminal) {
            if (pendingErr != null) {
                // Nothing in these accumulators was shown to the caller. Never smuggle a failed provider's buffered
                // draft through message_end: AgentLoop could expose it while Router still considers failover safe.
                assistantText.setLength(0);
                thinkingText.setLength(0);
                thinkingSignature[0] = null;
                toolCalls = List.of();
                nativeContentJson = null;
                partialTools.clear();
                nativeContentBlocks.clear();
                nativeText.clear();
                nativeThinking.clear();
                nativeServerInputs.clear();
            } else if ("refusal".equals(stopReason[0])) {
                // Current Claude models may stream a plausible partial answer before the terminal refusal. None of that text,
                // thinking, signed replay state, or tool state is safe to persist or execute after refusal.
                String explanation = refusalExplanation(stopDetails[0]);
                assistantText.setLength(0);
                assistantText.append(explanation);
                thinkingText.setLength(0);
                thinkingSignature[0] = null;
                toolCalls = List.of();
                nativeContentJson = null;
                partialTools.clear();
                nativeContentBlocks.clear();
                nativeText.clear();
                nativeThinking.clear();
            }
            if (pendingErr == null) {
                if (thinkingText.length() > 0) {
                    onEvent.accept(StreamEvent.of("thinking_delta", Map.of("text", thinkingText.toString())));
                }
                if (assistantText.length() > 0) {
                    // A single terminal flush keeps the UI and persisted message aligned with the accepted terminal
                    // state (normal answer or refusal explanation).
                    onEvent.accept(StreamEvent.of("text_delta", Map.of("text", assistantText.toString())));
                }
            }
        }

        if (messageStarted.get()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("content", assistantText.toString());
            payload.put("toolCalls", toolCalls);
            if (thinkingText.length() > 0) {
                payload.put("reasoning", thinkingText.toString());
            }
            // Newest models can omit displayed thinking entirely while still returning a signature. Persist that
            // signature even when reasoning text is empty; otherwise AgentLoop cannot retain the continuation state.
            if (thinkingSignature[0] != null) {
                payload.put("reasoningProvider", outputProviderId(requestModel));
                payload.put("reasoningToken", thinkingSignature[0]);
            }
            if (nativeContentJson != null) {
                if (!continuation.hasSegments()) nativeContentJson = tagNativeContent(nativeContentJson, body);
                payload.put("responseOutputProvider", outputProviderId(requestModel));
                payload.put("responseOutputItems", nativeContentJson);
            }
            if (stopReason[0] != null) {
                payload.put("stopReason", stopReason[0]); // informational; AgentLoop ignores unknown keys
            }
            if ("refusal".equals(stopReason[0])) {
                payload.put("refusal", true);
            }
            if (pendingErr != null) {
                payload.put("interrupted", true);
                payload.put("error", pendingErr.code);
                if (stopReason[0] != null) {
                    payload.put("incompleteReason", stopReason[0]);
                }
            }
            onEvent.accept(StreamEvent.of("message_end", payload));
        }
        if (log.isDebugEnabled()) {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            boolean interrupted = pendingErr != null;
            String stopStr = pendingErr != null ? pendingErr.code : (stopReason[0] != null ? stopReason[0] : "stop");
            ProviderStreamLogger.logResponse(log, requestId, name, assistantText.toString(), thinkingText.toString(),
                toolCalls, stopStr, interrupted, durationMs);
        }
        if (pendingErr != null) {
            throw withExchange(pendingErr, call);
        }
    }

    /** Serialise a request body to JSON for DEBUG logging; never throws (falls back to toString on error). */
    private static String toJson(Object body) {
        try {
            return M.writeValueAsString(body);
        } catch (Exception e) {
            return String.valueOf(body);
        }
    }

    private static String peek(Object argsObj) {
        if (argsObj == null) {
            return "";
        }
        String all = argsObj.toString();
        return all.length() > 200 ? all.substring(0, 200) + "…" : all;
    }

    /**
     * Validate Anthropic's terminal state. A {@code null} return means the response is safe to commit; every other
     * value deliberately becomes an interrupted turn so AgentLoop cannot execute any client tool blocks it saw.
     */
    private static ProviderException terminalFailure(String stopReason, String stopDetails, boolean allowToolUse) {
        String details = stopDetails == null || stopDetails.isBlank() ? "" : " (stop_details=" + stopDetails + ")";
        if ("end_turn".equals(stopReason) || "stop_sequence".equals(stopReason) || "refusal".equals(stopReason)
            || allowToolUse && "tool_use".equals(stopReason)) {
            return null;
        }
        if (stopReason == null || stopReason.isBlank()) {
            return new ProviderException("parse", "Anthropic response ended without stop_reason");
        }
        return switch (stopReason) {
            case "max_tokens", "model_context_window_exceeded" -> new ProviderException("incomplete",
                "Anthropic response was truncated: " + stopReason + details);
            case "pause_turn" ->
                new ProviderException("pause_turn", "Anthropic paused the turn; exact continuation is required" + details);
            case "tool_use" -> new ProviderException("unexpected_tool_use",
                "Anthropic one-shot response requested a tool that this call cannot execute" + details);
            default -> new ProviderException("parse", "Unknown Anthropic stop_reason: " + stopReason + details);
        };
    }

    /** Extract a structured refusal explanation without ever exposing its preceding partial output. */
    private static String refusalExplanation(String stopDetails) {
        if (stopDetails != null && !stopDetails.isBlank()) {
            try {
                JsonNode details = M.readTree(stopDetails);
                String explanation = details.path("explanation").asText("").trim();
                if (!explanation.isEmpty()) {
                    return explanation;
                }
            } catch (Exception ex) {
                log.debug("Could not parse Anthropic refusal stop_details: {}", ex.toString());
            }
        }
        return REFUSAL_FALLBACK;
    }

    private static String anthropicErrorCode(String type) {
        return switch (type) {
            case "invalid_request_error" -> "400";
            case "authentication_error" -> "401";
            case "permission_error" -> "403";
            case "not_found_error" -> "404";
            case "request_too_large" -> "413";
            case "rate_limit_error" -> "429";
            case "overloaded_error" -> "529";
            default -> "500";
        };
    }

    /** Materialise streamed fields and serialise the complete ordered assistant content array for stateless replay. */
    private static String serializeNativeContent(Map<Integer, ObjectNode> blocks,
        Map<Integer, StringBuilder> textByIndex, Map<Integer, StringBuilder> thinkingByIndex,
        Map<Integer, JsonNode> toolInputs, Map<Integer, StringBuilder> serverToolInputs) {
        if (blocks.size() > MAX_NATIVE_OUTPUT_ITEMS) {
            log.warn("Anthropic native output has {} blocks; omitting replay metadata above limit {}", blocks.size(),
                MAX_NATIVE_OUTPUT_ITEMS);
            return null;
        }
        List<JsonNode> ordered = new ArrayList<>(blocks.size());
        for (Map.Entry<Integer, ObjectNode> entry : blocks.entrySet()) {
            int index = entry.getKey();
            ObjectNode block = entry.getValue().deepCopy();
            String type = block.path("type").asText("");
            if ("text".equals(type)) {
                StringBuilder text = textByIndex.get(index);
                block.put("text", text == null ? block.path("text").asText("") : text.toString());
            } else if ("thinking".equals(type)) {
                StringBuilder thinking = thinkingByIndex.get(index);
                block.put("thinking", thinking == null ? block.path("thinking").asText("") : thinking.toString());
            } else if ("tool_use".equals(type) && toolInputs.containsKey(index)) {
                block.set("input", toolInputs.get(index));
            } else if ("server_tool_use".equals(type)) {
                StringBuilder partial = serverToolInputs.get(index);
                if (partial != null && partial.length() > 0) {
                    try {
                        JsonNode input = M.readTree(partial.toString());
                        if (!input.isObject()) {
                            throw new IllegalArgumentException("server tool input must be a JSON object");
                        }
                        block.set("input", input);
                    } catch (Exception ex) {
                        log.warn("Anthropic server_tool_use {} has invalid streamed input: {}", index,
                            ex.getMessage());
                        return null;
                    }
                }
            }
            ordered.add(block);
        }
        try {
            String json = M.writeValueAsString(ordered);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_NATIVE_OUTPUT_BYTES) {
                log.warn("Anthropic native output exceeds {} bytes; omitting replay metadata",
                    MAX_NATIVE_OUTPUT_BYTES);
                return null;
            }
            return json;
        } catch (Exception ex) {
            throw new ProviderException("parse", "Could not persist Anthropic native content: " + ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean hasServerTool(Map<String, Object> body) {
        Object tools = body.get("tools");
        if (!(tools instanceof List<?> list)) {
            return false;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> tool && tool.get("type") instanceof String type
                && !"function".equals(type)) {
                return true;
            }
        }
        return false;
    }

    private static String continuationIdempotencyKey(String original, int continuationCount) {
        if (original == null || original.isBlank() || continuationCount == 0) {
            return original;
        }
        return original + "-anthropic-pause-" + continuationCount;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> continuationBody(Map<String, Object> body, String nativeContentJson) {
        try {
            List<Object> pausedContent = M.readValue(nativeContentJson, List.class);
            Map<String, Object> next = new LinkedHashMap<>(body);
            Object rawMessages = body.get("messages");
            List<Object> messages = rawMessages instanceof List<?> list ? new ArrayList<>(list) : new ArrayList<>();
            Map<String, Object> assistant = new LinkedHashMap<>();
            assistant.put("role", "assistant");
            assistant.put("content", pausedContent);
            messages.add(assistant);
            next.put("messages", messages);
            return next;
        } catch (Exception ex) {
            throw new ProviderException("parse",
                "Could not build Anthropic pause_turn continuation: " + ex.getMessage());
        }
    }

    private static String combineNativeContent(List<String> pausedSegments, String finalSegment) {
        if (finalSegment == null || finalSegment.isBlank()) {
            return null;
        }
        try {
            ArrayNode combined = M.createArrayNode();
            for (String segment : pausedSegments) {
                JsonNode parsed = M.readTree(segment);
                if (!parsed.isArray()) {
                    return null;
                }
                parsed.forEach(combined::add);
                combined.addObject().put("type", CONTINUATION_BOUNDARY_TYPE);
            }
            JsonNode parsedFinal = M.readTree(finalSegment);
            if (!parsedFinal.isArray()) {
                return null;
            }
            parsedFinal.forEach(combined::add);
            if (combined.size() > MAX_NATIVE_OUTPUT_ITEMS) {
                log.warn("Anthropic continued native output has {} blocks; omitting replay metadata above limit {}",
                    combined.size(), MAX_NATIVE_OUTPUT_ITEMS);
                return null;
            }
            String json = M.writeValueAsString(combined);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_NATIVE_OUTPUT_BYTES) {
                log.warn("Anthropic continued native output exceeds {} bytes; omitting replay metadata",
                    MAX_NATIVE_OUTPUT_BYTES);
                return null;
            }
            return json;
        } catch (Exception ex) {
            log.warn("Could not combine Anthropic pause_turn content: {}", ex.getMessage());
            return null;
        }
    }

    private static boolean isReplayableContentBlockType(String type) {
        return REPLAYABLE_CONTENT_BLOCK_TYPES.contains(type) || isServerToolResultType(type);
    }

    private static boolean isServerToolResultType(String type) {
        return type != null && type.endsWith("_tool_result") && !"tool_result".equals(type);
    }

    private static final class ContinuationState {
        private final List<String> nativeSegments = new ArrayList<>();
        private final StringBuilder visibleText = new StringBuilder();
        private final StringBuilder thinkingText = new StringBuilder();
        private long nativeBytes;
        private int nativeItems;

        void add(String nativeContent, int itemCount, String text, String thinking) {
            if (nativeContent == null || nativeContent.isBlank()) {
                throw new ProviderException("parse", "Anthropic pause_turn native content is blank");
            }
            long segmentBytes = nativeContent.getBytes(StandardCharsets.UTF_8).length;
            long nextBytes = nativeBytes + segmentBytes + CONTINUATION_BOUNDARY_BYTES + 2L;
            int nextItems = nativeItems + itemCount + 1;
            if (nextBytes > MAX_NATIVE_OUTPUT_BYTES || nextItems > MAX_NATIVE_OUTPUT_ITEMS) {
                throw new ProviderException("agent_oversize",
                    "Anthropic pause_turn continuation exceeded native replay envelope");
            }
            appendBounded(visibleText, text, MAX_ASSISTANT_CHARS, "continued assistant response");
            appendBounded(thinkingText, thinking, MAX_REASONING_CHARS, "continued reasoning response");
            nativeSegments.add(nativeContent);
            nativeBytes = nextBytes;
            nativeItems = nextItems;
        }

        void requireFinal(String nativeContent, int itemCount, int textChars, int thinkingChars) {
            if (nativeContent == null || nativeContent.isBlank()) {
                throw new ProviderException("incomplete",
                    "Anthropic pause_turn final response lacked complete native assistant content");
            }
            if (visibleText.length() > MAX_ASSISTANT_CHARS - textChars) {
                throw new ProviderException("agent_oversize",
                    "continued assistant response exceeded " + MAX_ASSISTANT_CHARS + " chars");
            }
            if (thinkingText.length() > MAX_REASONING_CHARS - thinkingChars) {
                throw new ProviderException("agent_oversize",
                    "continued reasoning response exceeded " + MAX_REASONING_CHARS + " chars");
            }
            long finalBytes = nativeContent.getBytes(StandardCharsets.UTF_8).length;
            if (nativeBytes + finalBytes + 1L > MAX_NATIVE_OUTPUT_BYTES
                || nativeItems + itemCount > MAX_NATIVE_OUTPUT_ITEMS) {
                throw new ProviderException("agent_oversize",
                    "Anthropic continued output exceeded native replay envelope");
            }
        }

        boolean hasSegments() {
            return !nativeSegments.isEmpty();
        }

        List<String> nativeSegments() {
            return nativeSegments;
        }

        String visibleText() {
            return visibleText.toString();
        }

        String thinkingText() {
            return thinkingText.toString();
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
        String model = req.getModel();
        Map<String, Object> body = buildBody(req, false);
        String requestModel = model == null ? defaultModel : model;
        Call call = newCall(buildRequest(body, null));
        return runWithTimeout(call, timeoutMs, resp -> {
            JsonNode json = M.readTree(readBoundedSuccessBody(resp));
            JsonNode usage = json.path("usage");
            ProviderTokenUsage tokenUsage = usage.isObject() && !usage.isEmpty()
                ? ProviderUsage.anthropic(usage) : null;
            if (tokenUsage != null) tokenUsage.record(metrics);
            JsonNode detailsNode = json.get("stop_details");
            String details = detailsNode == null || detailsNode.isNull() ? null : detailsNode.toString();
            String stopReason = json.path("stop_reason").asText("");
            ProviderException terminal = terminalFailure(stopReason, details, false);
            if (terminal != null) {
                throw terminal.withUsage(tokenUsage);
            }
            if (modelCapabilities.profile(requestModel).strictTerminal() && "refusal".equals(stopReason)) {
                return new OneShotResult(refusalExplanation(details), name, requestModel, tokenUsage);
            }
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : json.path("content")) {
                if ("text".equals(part.path("type").asText())) {
                    sb.append(part.path("text").asText(""));
                }
            }
            return new OneShotResult(sb.toString(), name, requestModel, tokenUsage);
        });
    }

    @Override public OneShotResult compact(ChatRequest request, long timeoutMs) {
        String model = request.getModel() == null ? defaultModel : request.getModel();
        long started = System.nanoTime();
        if (!modelCapabilities.profile(model).thinkingPrefixBound() || !supportsCompaction(model, timeoutMs)) {
            throw new ProviderException("native_compaction_unavailable", "Endpoint has not declared native compaction",
                com.harnex.net.HttpExchangeState.Acceptance.NOT_SENT, null, 0);
        }
        PreparedBody prepared;
        try (var scope = ProviderCallScope.preserveCompactionToolDefinitions()) {
            prepared = prepareBody(request, false);
        }
        Map<String, Object> body = prepared.body();
        body.put("compaction", Map.of("type", "summarize"));
        body.remove("context_management");
        body.remove("stop_sequences");
        body.remove("tool_choice");
        Request httpRequest = buildRequest(body, request.getIdempotencyKey()).newBuilder()
            .header("anthropic-beta", compactionBetas()).build();
        long remaining = timeoutMs - java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        if (remaining <= 0) throw new ProviderException("timeout", "Native compaction budget exhausted");
        Call call = newCall(httpRequest);
        return runWithTimeout(call, remaining, response -> {
            JsonNode root = M.readTree(readBoundedSuccessBody(response));
            ProviderTokenUsage usage = root.path("usage").isObject() ? ProviderUsage.anthropic(root.path("usage")) : null;
            if (usage != null && metrics != null) usage.record(metrics);
            JsonNode content = root.path("content");
            if (!"compaction".equals(root.path("stop_reason").asText()) || !content.isArray() || content.size() != 1
                || !"compaction".equals(content.get(0).path("type").asText())
                || !content.get(0).path("signature").isTextual() || !content.get(0).path("content").isTextual()
                || content.get(0).path("signature").asText("").isBlank()
                || content.get(0).path("content").asText("").isBlank()) {
                throw new ProviderException("incomplete", "Native compaction did not return a complete signed summary").withUsage(usage);
            }
            if (content.toString().getBytes(StandardCharsets.UTF_8).length > MAX_NATIVE_OUTPUT_BYTES) {
                throw new ProviderException("agent_oversize", "Native compaction exceeded replay envelope").withUsage(usage);
            }
            List<Map<String, Object>> blocks = M.convertValue(content, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            ChatMessage summary = ChatMessage.assistant("[Compressed history summary]\n" + content.get(0).path("content").asText());
            summary.setResponseOutputProvider(outputProviderId(model));
            summary.setResponseOutputItems(AnthropicPrefixState.compacted(blocks, body, prepared.prefixStates()));
            return new OneShotResult(M.writeValueAsString(summary), name, model, usage);
        });
    }

    boolean canCompactNatively(String model) {
        return model != null && modelCapabilities.profile(model).thinkingPrefixBound()
            && !Boolean.FALSE.equals(compactionCapabilities.get(model));
    }

    boolean supportsCompaction(String model, long budgetMs) {
        Boolean known = compactionCapabilities.get(model);
        if (known != null) return known;
        // A model name or relay URL is not proof. Ask the endpoint's non-billable Models API with the beta enabled.
        var url = Objects.requireNonNull(okhttp3.HttpUrl.parse(resolveEndpoint(baseUrl, "/messages"))).newBuilder();
        url.removePathSegment(url.build().pathSize() - 1).addPathSegment("models").addPathSegment(model);
        Request.Builder request = new Request.Builder().url(url.build()).get()
            .header("anthropic-version", API_VERSION).header("anthropic-beta", compactionBetas());
        if (apiKey != null && !apiKey.isBlank()) request.header("x-api-key", apiKey);
        try {
            boolean supported = runWithTimeout(newCall(request.build()), Math.max(1, Math.min(5000, budgetMs)), response -> {
                JsonNode capability = M.readTree(readBoundedSuccessBody(response)).path("capabilities").path("compaction");
                return capability.isBoolean() ? capability.booleanValue()
                    : capability.path("supported").isBoolean() && capability.path("supported").booleanValue();
            });
            compactionCapabilities.put(model, supported);
            return supported;
        } catch (ProviderException failure) {
            if ("cancelled".equals(failure.code)) throw failure;
            if (Set.of("400", "404", "405", "501").contains(failure.code)) compactionCapabilities.put(model, false);
            // Unconfirmed metadata leaves the client summarizer available; transient errors are not cached.
            return false;
        }
    }

    private String compactionBetas() {
        var betas = new LinkedHashSet<String>();
        if (opts.betas() != null) betas.addAll(opts.betas());
        if (opts.emulation()) betas.addAll(CC_BETAS);
        betas.add(COMPACTION_BETA);
        return String.join(",", betas);
    }

    /** Build the JSON body. {@code stream=true} adds the SSE flag; oneShot sends {@code stream=false} explicitly. */
    private Map<String, Object> buildBody(ChatRequest req, boolean stream) {
        return prepareBody(req, stream).body();
    }

    private record PreparedBody(Map<String, Object> body, List<String> prefixStates) {}

    private PreparedBody prepareBody(ChatRequest req, boolean stream) {
        String model = req.getModel() == null ? defaultModel : req.getModel();
        ProviderModelCapabilities.Profile modelProfile = modelCapabilities.profile(model);
        if (!modelProfile.anthropicApi()) {
            throw new ProviderException("config", model
                + " is not configured for the Anthropic Messages API; check the provider profile");
        }
        if (stream && !modelProfile.streaming()) {
            throw new ProviderException("config", model + " does not declare streaming support");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);

        // Hoist role=system messages out of the array — Anthropic rejects them inline.
        StringBuilder stableSystem = new StringBuilder();
        StringBuilder dynamicSystem = new StringBuilder();
        List<ChatMessage> rest = new ArrayList<>();
        boolean leadingSystemPrefix = true;
        for (ChatMessage m : req.getMessages()) {
            if ("system".equals(m.getRole())) {
                appendSystem(leadingSystemPrefix ? stableSystem : dynamicSystem, m.getContent());
            } else {
                leadingSystemPrefix = false;
                rest.add(m);
            }
        }
        if (stableSystem.length() > 0 || dynamicSystem.length() > 0) {
            boolean cacheSystem = opts.promptCaching()
                && modelProfile.promptCacheMode()
                    == ProviderModelCapabilities.PromptCacheMode.CONTENT_CACHE_CONTROL;
            if (cacheSystem) {
                List<Map<String, Object>> sysBlocks = new ArrayList<>();
                if (opts.emulation()) {
                    sysBlocks.add(buildBillingBlock(req));
                }
                if (stableSystem.length() > 0) {
                    // Render order is tools → system → messages. Mark only the genuinely stable leading system
                    // prefix; later middleware reminders must not invalidate it or be cached as cross-turn policy.
                    Map<String, Object> stable = textBlock(stableSystem.toString());
                    stable.put("cache_control", Map.of("type", "ephemeral"));
                    sysBlocks.add(stable);
                }
                if (dynamicSystem.length() > 0) {
                    sysBlocks.add(textBlock(dynamicSystem.toString()));
                }
                body.put("system", sysBlocks);
            } else {
                if (opts.emulation()) {
                    // Convert string system to block form so we can prepend the billing header
                    List<Map<String, Object>> sysBlocks = new ArrayList<>();
                    sysBlocks.add(buildBillingBlock(req));
                    if (stableSystem.length() > 0) {
                        sysBlocks.add(textBlock(stableSystem.toString()));
                    }
                    if (dynamicSystem.length() > 0) {
                        sysBlocks.add(textBlock(dynamicSystem.toString()));
                    }
                    body.put("system", sysBlocks);
                } else {
                    String combined = stableSystem.length() == 0 ? dynamicSystem.toString()
                        : dynamicSystem.length() == 0 ? stableSystem.toString()
                            : stableSystem + "\n\n" + dynamicSystem;
                    body.put("system", combined);
                }
            }
        } else if (opts.emulation()) {
            // No system content but CC emulation still needs the billing header
            body.put("system", List.of(buildBillingBlock(req)));
        }

        body.put("messages", toApiMessages(rest, model));
        ProviderCapabilityRequest.applyOutputTokenLimit(body, modelProfile, req.getMaxTokens(), opts.defaultMaxTokens());

        boolean adaptiveThinkingEnabled =
            ProviderCapabilityRequest.applyThinking(body, modelProfile, opts.thinking(), model);
        if (opts.sendTemperature() && req.getTemperature() != null
            && ProviderCapabilityRequest.sendSampling(modelProfile, adaptiveThinkingEnabled)) {
            body.put("temperature", req.getTemperature());
        }
        List<Map<String, Object>> requestedTools = req.getTools() == null ? List.of() : req.getTools();
        String nativeWebSearchType =
            ProviderCapabilityRequest.nativeWebSearchType(modelProfile, opts.nativeWebSearch(), model, req);
        boolean nativeWebSearch = nativeWebSearchType != null;
        boolean hasClientFunctionTools = requestedTools.stream()
            .anyMatch(tool -> isEffectiveClientFunctionTool(tool, nativeWebSearch));
        ProviderCapabilityRequest.requireFunctionTools(modelProfile, hasClientFunctionTools, model);
        List<Map<String, Object>> apiTools = toApiTools(requestedTools, nativeWebSearchType);
        String effort = ProviderCapabilityRequest.reasoningEffort(modelProfile,
            req.getReasoningEffort() != null ? req.getReasoningEffort() : opts.effort(), hasClientFunctionTools,
            model,
            "output_config.effort");
        ProviderCapabilityRequest.requireThinkingForEffort(modelProfile, adaptiveThinkingEnabled, effort, model);
        if (effort != null) {
            body.put("output_config", Map.of("effort", effort));
        }
        if (stream) {
            body.put("stream", true);
        } else {
            body.put("stream", false);
        }
        if (!apiTools.isEmpty()) {
            body.put("tools", apiTools);
        }
        ProviderCapabilityRequest.applyRequestOptions(body, modelProfile);
        PromptCachePlan.apply(body, req, modelProfile, opts.promptCaching());
        List<String> states = AnthropicPrefixState.sanitize(body, modelProfile.thinkingPrefixBound());
        return new PreparedBody(body, states);
    }

    private String tagNativeContent(String content, Map<String, Object> body) {
        return modelCapabilities.profile(String.valueOf(body.get("model"))).thinkingPrefixBound()
            ? AnthropicPrefixState.tag(content, body) : content;
    }

    private static void appendSystem(StringBuilder target, String content) {
        if (content == null || content.isEmpty()) {
            return;
        }
        if (target.length() > 0) {
            target.append("\n\n");
        }
        target.append(content);
    }

    private static Map<String, Object> textBlock(String text) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);
        return block;
    }

    /**
     * Translate internal {@link ChatMessage}s into Anthropic typed content blocks.
     *
     * <p>
     * New turns prefer {@link ChatMessage#getResponseOutputItems()} so every native block and its original order are
     * replayed verbatim. Legacy threads fall back to reconstructing text/tool_use plus any adjacent signed thinking
     * blocks. Replay is gated by what Anthropic actually returned, not today's thinking option: current models may emit
     * default/always-on thinking even when the local option says {@code off}.
     */
    private List<Map<String, Object>> toApiMessages(List<ChatMessage> msgs, String model) {
        List<Map<String, Object>> out = new ArrayList<>();
        AttachmentBudget attachmentBudget = new AttachmentBudget();
        Map<ChatMessage, List<Map<String, Object>>> preparedUserContent = new IdentityHashMap<>();
        // Anthropic's native attachment budget is request-wide. Allocate it from newest to oldest so a historical
        // upload cannot force the current turn into fallback text, then emit messages in their original order.
        for (int index = msgs.size() - 1; index >= 0; index--) {
            ChatMessage message = msgs.get(index);
            if (message != null && "user".equals(message.getRole()) && message.getAttachments() != null
                && !message.getAttachments().isEmpty()) {
                preparedUserContent.put(message, userContent(message, model, attachmentBudget));
            }
        }
        boolean nativeReplay = modelCapabilities.profile(model).reasoningReplay()
            == ProviderModelCapabilities.ReasoningReplay.NATIVE_ITEMS;
        List<Map<String, Object>> pendingThinking = null;
        int i = 0;
        while (i < msgs.size()) {
            ChatMessage m = msgs.get(i);
            if (m.isReasoningBlock()) {
                List<Map<String, Object>> candidates = new ArrayList<>();
                int nextIndex = i;
                while (nextIndex < msgs.size() && msgs.get(nextIndex).isReasoningBlock()) {
                    if (nativeReplay) {
                        candidates.addAll(storedThinkingBlocks(msgs.get(nextIndex), model));
                    }
                    nextIndex++;
                }
                ChatMessage next = nextIndex < msgs.size() ? msgs.get(nextIndex) : null;
                boolean followedByClientTools = next != null && "assistant".equals(next.getRole())
                    && next.getToolCalls() != null && !next.getToolCalls().isEmpty();
                pendingThinking = followedByClientTools && !candidates.isEmpty() ? candidates : null;
                i = nextIndex;
                continue;
            }
            if ("tool".equals(m.getRole())) {
                pendingThinking = null;
                List<Map<String, Object>> blocks = new ArrayList<>();
                while (i < msgs.size() && "tool".equals(msgs.get(i).getRole())) {
                    ChatMessage tm = msgs.get(i);
                    Map<String, Object> b = new LinkedHashMap<>();
                    b.put("type", "tool_result");
                    b.put("tool_use_id", tm.getToolCallId() == null ? "" : tm.getToolCallId());
                    b.put("content", tm.getContent() == null ? "" : tm.getContent());
                    blocks.add(b);
                    i++;
                }
                Map<String, Object> userMsg = new LinkedHashMap<>();
                userMsg.put("role", "user");
                userMsg.put("content", blocks);
                out.add(userMsg);
                continue;
            }
            Map<String, Object> j = new LinkedHashMap<>();
            j.put("role", m.getRole());
            if ("assistant".equals(m.getRole())) {
                List<List<Map<String, Object>>> storedMessages =
                    nativeReplay ? storedNativeMessages(m, model) : null;
                if (storedMessages != null) {
                    // It already contains thinking/redacted_thinking/text/tool_use in the exact response order.
                    // Prepending reconstructed reasoning or calls would duplicate blocks and invalidate signatures.
                    for (List<Map<String, Object>> storedContent : storedMessages) {
                        Map<String, Object> nativeMessage = new LinkedHashMap<>();
                        nativeMessage.put("role", "assistant");
                        nativeMessage.put("content", storedContent);
                        out.add(nativeMessage);
                    }
                    pendingThinking = null;
                    i++;
                    continue;
                }
            }
            if ("assistant".equals(m.getRole()) && m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
                List<Map<String, Object>> blocks = new ArrayList<>();
                if (pendingThinking != null) {
                    blocks.addAll(pendingThinking); // all thinking blocks MUST precede the tool_use they produced
                }
                String text = m.getContent();
                if (text != null && !text.isEmpty()) {
                    blocks.add(Map.of("type", "text", "text", text));
                }
                for (ToolCall tc : m.getToolCalls()) {
                    String tcId = tc.getId();
                    String tcName = tc.getName();
                    if (tcId == null || tcId.isBlank() || tcName == null || tcName.isBlank()) {
                        log.error("AnthropicProvider: dropping replayed tool_call with null/blank id/name "
                            + "(id={}, name={}); thread had stale assistant tool_calls", tcId, tcName);
                        continue;
                    }
                    Map<String, Object> b = new LinkedHashMap<>();
                    b.put("type", "tool_use");
                    b.put("id", tcId);
                    b.put("name", tcName);
                    Object input;
                    String args = tc.getArguments();
                    if (args == null || args.isBlank()) {
                        input = Map.of();
                    } else {
                        try {
                            input = M.readValue(args, Map.class);
                        } catch (Exception ex) {
                            log.error(
                                "AnthropicProvider: tool_call {} (id={}) has malformed JSON arguments, sending "
                                    + "empty input: {} | head: {}",
                                tc.getName(), tc.getId(), ex.getMessage(),
                                args.substring(0, Math.min(args.length(), 200)));
                            input = Map.of();
                        }
                    }
                    b.put("input", input);
                    blocks.add(b);
                }
                j.put("content", blocks);
            } else if ("user".equals(m.getRole()) && m.getAttachments() != null
                && !m.getAttachments().isEmpty()) {
                j.put("content", preparedUserContent.get(m));
            } else {
                j.put("content", m.getContent() == null ? "" : m.getContent());
            }
            pendingThinking = null;
            out.add(j);
            i++;
        }
        return out;
    }

    /** Build Anthropic user content with every attachment block (or visible fallback) before the user's text. */
    private List<Map<String, Object>> userContent(ChatMessage message, String model, AttachmentBudget budget) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        ProviderModelCapabilities.Profile profile = modelCapabilities.profile(model);
        boolean imageCapable = profile.exactModel() ? profile.imageInput()
            : configuredAttachmentCapability(model, imageInputModels, true);
        boolean pdfCapable = profile.exactModel() ? profile.supportsModality("pdf")
            : configuredAttachmentCapability(model, pdfInputModels, true);

        for (MessageAttachment attachment : message.getAttachments()) {
            if (attachment == null) {
                blocks.add(textBlock("[an attachment was not sent inline because its metadata is missing]"));
                continue;
            }
            String basename = safeBasename(attachment.getName());
            String displayName = basename == null ? "attachment" : basename;
            String location = visibleAttachmentPath(attachment, basename);
            String mime = attachment.getMime() == null ? "" : attachment.getMime().trim().toLowerCase(Locale.ROOT);
            boolean image = mime.startsWith("image/");
            boolean pdf = PDF_MIME.equals(mime);

            if ((image || pdf) && !visionEnabled) {
                blocks.add(textBlock("[attachment " + displayName
                    + " was not sent inline because native attachment input is disabled; use file_read"
                    + (image ? "/OCR" : "") + " on " + location + "]"));
                continue;
            }
            if (image && !SUPPORTED_IMAGE_MIMES.contains(mime)) {
                blocks.add(textBlock("[image " + displayName + " was not sent inline: unsupported MIME " + mime
                    + "; use file_read/OCR on " + location + "]"));
                continue;
            }
            if (image && !imageCapable) {
                blocks.add(textBlock("[image " + displayName + " was not sent inline because model " + model
                    + " is not explicitly configured for image input; use file_read/OCR on " + location + "]"));
                continue;
            }
            if (pdf && !pdfCapable) {
                blocks.add(textBlock("[PDF " + displayName + " was not sent inline because model " + model
                    + " is not explicitly configured for PDF input; use file_read on " + location + "]"));
                continue;
            }
            if (!image && !pdf) {
                blocks.add(textBlock("[attached file " + displayName + " is available at " + location
                    + "; use file_read to inspect it]"));
                continue;
            }
            if (!budget.canAcceptAnother()) {
                blocks.add(textBlock("[attachment " + displayName + " was not sent inline: aggregate request limit "
                    + "reached; use file_read" + (image ? "/OCR" : "") + " on " + location + "]"));
                continue;
            }

            InlineAttachment inline = readAttachment(attachment, basename, budget.remainingBytes());
            if (inline == null) {
                blocks.add(textBlock("[attachment " + displayName
                    + " was not sent inline because it is unsafe, unreadable, or exceeds the byte limit; use "
                    + "file_read" + (image ? "/OCR" : "") + " on " + location + "]"));
                continue;
            }
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("type", "base64");
            source.put("media_type", mime);
            source.put("data", inline.base64());
            Map<String, Object> nativeBlock = new LinkedHashMap<>();
            nativeBlock.put("type", image ? "image" : "document");
            nativeBlock.put("source", source);
            blocks.add(nativeBlock);
            budget.accept(inline.bytes());
        }

        String text = message.getContent();
        if (text != null && !text.isEmpty()) {
            blocks.add(textBlock(text));
        }
        if (blocks.isEmpty()) {
            blocks.add(textBlock(""));
        }
        return blocks;
    }

    private static boolean configuredAttachmentCapability(String model, Set<String> configured,
        boolean vendorDoesNotDeny) {
        String normalised = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
        return vendorDoesNotDeny && configured.contains(normalised);
    }

    private static Set<String> normaliseModelIds(Set<String> modelIds) {
        if (modelIds == null || modelIds.isEmpty()) {
            return Set.of();
        }
        Set<String> normalised = new LinkedHashSet<>();
        for (String model : modelIds) {
            if (model != null && !model.isBlank()) {
                normalised.add(model.trim().toLowerCase(Locale.ROOT));
            }
        }
        return Collections.unmodifiableSet(normalised);
    }

    private String visibleAttachmentPath(MessageAttachment attachment, String basename) {
        String threadId = attachment.getThreadId();
        if (uploadsRoot != null && basename != null && Ids.isSafe(threadId)) {
            try {
                return uploadsRoot.resolve(threadId).resolve(basename).normalize().toString();
            } catch (RuntimeException ignored) {
                // Fall through to a non-path display; never trust attachment.path as a cross-thread fallback.
            }
        }
        return basename == null ? "the owning thread's upload directory" : basename;
    }

    private static String safeBasename(String name) {
        if (name == null || name.isBlank() || name.indexOf('\0') >= 0 || name.contains("/") || name.contains("\\")
            || ".".equals(name) || "..".equals(name)) {
            return null;
        }
        try {
            Path parsed = Path.of(name);
            return parsed.getNameCount() == 1 && name.equals(parsed.getFileName().toString()) ? name : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * Resolve and bounded-read exactly {@code <uploadsRoot>/<threadId>/<basename>}. There is deliberately no legacy
     * basename scan: an attachment without ownership cannot borrow an identically named file from another thread.
     */
    private InlineAttachment readAttachment(MessageAttachment attachment, String basename, long remainingBytes) {
        if (uploadsRoot == null || basename == null || maxAttachmentBytes <= 0 || remainingBytes <= 0
            || !Ids.isSafe(attachment.getThreadId())) {
            return null;
        }
        long readLimit = Math.min(maxAttachmentBytes, remainingBytes);
        if (readLimit <= 0 || readLimit > Integer.MAX_VALUE - 8L
            || attachment.getSize() > 0 && attachment.getSize() > readLimit) {
            return null;
        }
        try {
            Path root = uploadsRoot;
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
                return null;
            }
            Path threadDir = root.resolve(attachment.getThreadId()).normalize();
            Path target = threadDir.resolve(basename).normalize();
            if (!threadDir.startsWith(root) || !target.startsWith(threadDir)
                || !Files.isDirectory(threadDir, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(threadDir)
                || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
                return null;
            }

            Path realRoot = root.toRealPath();
            Path realThread = threadDir.toRealPath();
            Path realTarget = target.toRealPath();
            if (!realThread.startsWith(realRoot) || !realTarget.startsWith(realThread)) {
                return null;
            }
            long actualSize = Files.size(target);
            if (actualSize > readLimit || attachment.getSize() > 0 && attachment.getSize() != actualSize) {
                return null;
            }

            ByteArrayOutputStream bytes = new ByteArrayOutputStream((int)actualSize);
            try (InputStream in = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
                byte[] chunk = new byte[8192];
                long total = 0;
                int read;
                while ((read = in.read(chunk)) != -1) {
                    total += read;
                    if (total > readLimit) {
                        return null;
                    }
                    bytes.write(chunk, 0, read);
                }
                return new InlineAttachment(Base64.getEncoder().encodeToString(bytes.toByteArray()), total);
            }
        } catch (IOException | SecurityException ex) {
            log.debug("Anthropic attachment inline failed for {}: {}", basename, ex.toString());
            return null;
        }
    }

    private record InlineAttachment(String base64, long bytes) {}

    private final class AttachmentBudget {
        private long bytes;
        private int count;

        boolean canAcceptAnother() {
            return maxAttachmentsPerRequest > 0 && count < maxAttachmentsPerRequest && remainingBytes() > 0;
        }

        long remainingBytes() {
            return Math.max(0L, maxRequestAttachmentBytes - bytes);
        }

        void accept(long acceptedBytes) {
            bytes += acceptedBytes;
            count++;
        }
    }

    /**
     * Parse complete native content stored on an assistant turn. Internal boundary markers preserve the distinct
     * assistant messages produced by a pause_turn continuation while keeping the persisted field an array compatible
     * with AgentLoop's native tool-call integrity check.
     */
    @SuppressWarnings("unchecked")
    private List<List<Map<String, Object>>> storedNativeMessages(ChatMessage message, String model) {
        if (!outputStateProducedHere(message.getResponseOutputProvider(), model)
            || message.getResponseOutputItems() == null || message.getResponseOutputItems().isBlank()) {
            return null;
        }
        try {
            List<Object> parsed = M.readValue(message.getResponseOutputItems(), List.class);
            if (parsed.isEmpty()) {
                return null;
            }
            List<List<Map<String, Object>>> messages = new ArrayList<>();
            List<Map<String, Object>> blocks = new ArrayList<>(parsed.size());
            List<Map<String, Object>> nativeTools = new ArrayList<>();
            for (Object item : parsed) {
                if (!(item instanceof Map<?, ?> raw)) {
                    log.warn("Anthropic stored native content contains a non-object block; reconstructing");
                    return null;
                }
                Object type = raw.get("type");
                if (CONTINUATION_BOUNDARY_TYPE.equals(type)) {
                    if (blocks.isEmpty()) {
                        log.warn("Anthropic stored continuation contains an empty assistant segment; reconstructing");
                        return null;
                    }
                    messages.add(blocks);
                    blocks = new ArrayList<>();
                    continue;
                }
                if (!(type instanceof String t) || !isReplayableContentBlockType(t) && !AnthropicPrefixState.isReceipt(t)) {
                    log.warn("Anthropic stored native content has unsupported block type {}; reconstructing", type);
                    return null;
                }
                Map<String, Object> block = (Map<String, Object>)item;
                blocks.add(block);
                if ("tool_use".equals(t)) {
                    nativeTools.add(block);
                }
            }
            if (blocks.isEmpty()) {
                log.warn("Anthropic stored native content ends with an empty assistant segment; reconstructing");
                return null;
            }
            messages.add(blocks);
            if (!nativeToolsMatch(nativeTools, message.getToolCalls())) {
                // Middleware may suppress or rewrite calls after persistence fields were captured. Replaying stale
                // native tool_use blocks would create orphan calls with no matching tool_result on the next request.
                log.warn("Anthropic stored native tool_use blocks no longer match the assistant toolCalls; reconstructing");
                return null;
            }
            StringBuilder visible = new StringBuilder();
            boolean hasReceipt = false;
            for (var segment : messages) for (var block : segment) {
                if (AnthropicPrefixState.isReceipt(block.get("type"))) hasReceipt = true;
                if ("text".equals(block.get("type"))) visible.append(Objects.toString(block.get("text"), ""));
                if ("compaction".equals(block.get("type"))) {
                    visible.append("[Compressed history summary]\n").append(Objects.toString(block.get("content"), ""));
                }
            }
            if (hasReceipt && !visible.toString().equals(Objects.toString(message.getContent(), ""))) return null;
            return messages;
        } catch (Exception ex) {
            log.warn("Anthropic could not parse stored native content for replay: {}", ex.getMessage());
            return null;
        }
    }

    private static boolean nativeToolsMatch(List<Map<String, Object>> nativeTools, List<ToolCall> toolCalls) {
        List<ToolCall> current = toolCalls == null ? List.of() : toolCalls;
        if (nativeTools.size() != current.size()) {
            return false;
        }
        for (int i = 0; i < nativeTools.size(); i++) {
            Map<String, Object> nativeTool = nativeTools.get(i);
            ToolCall currentTool = current.get(i);
            if (currentTool == null || !Objects.equals(nativeTool.get("id"), currentTool.getId())
                || !Objects.equals(nativeTool.get("name"), currentTool.getName())) {
                return false;
            }
            try {
                JsonNode nativeInput = M.valueToTree(nativeTool.getOrDefault("input", Map.of()));
                String arguments = currentTool.getArguments();
                JsonNode currentInput = arguments == null || arguments.isBlank() ? M.createObjectNode()
                    : M.readTree(arguments);
                if (!nativeInput.equals(currentInput)) {
                    return false;
                }
            } catch (Exception ex) {
                return false;
            }
        }
        return true;
    }

    /** Convert a legacy reasoning token into one or more native thinking blocks. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> storedThinkingBlocks(ChatMessage message, String model) {
        if (!outputStateProducedHere(message.getReasoningProvider(), model) || message.getReasoningToken() == null
            || message.getReasoningToken().isBlank()) {
            return List.of();
        }
        String token = message.getReasoningToken();
        if (token.stripLeading().startsWith("[")) {
            try {
                List<Object> parsed = M.readValue(token, List.class);
                List<Map<String, Object>> blocks = new ArrayList<>();
                for (Object item : parsed) {
                    if (!(item instanceof Map<?, ?> raw)) {
                        return List.of();
                    }
                    Object type = raw.get("type");
                    if (!"thinking".equals(type) && !"redacted_thinking".equals(type)) {
                        return List.of();
                    }
                    blocks.add((Map<String, Object>)item);
                }
                return blocks;
            } catch (Exception ex) {
                log.warn("Anthropic could not parse stored thinking blocks for replay: {}", ex.getMessage());
                return List.of();
            }
        }
        Map<String, Object> thinking = new LinkedHashMap<>();
        thinking.put("type", "thinking");
        thinking.put("thinking", message.getReasoning() == null ? "" : message.getReasoning());
        thinking.put("signature", token);
        return List.of(thinking);
    }

    private String outputProviderId(String model) {
        String id = OUTPUT_PROVIDER + ":" + name + ":" + normaliseModelId(model);
        return modelCapabilities.profile(model).thinkingPrefixBound() ? id + ":" + replayOwner : id;
    }

    private static String replayOwner(String endpoint, String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((endpoint + "\n" + Objects.toString(key, "")).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) { throw new IllegalStateException("Cannot identify replay owner", failure); }
    }

    private boolean outputStateProducedHere(String provenance, String model) {
        // A bare legacy tag cannot identify which Anthropic endpoint produced a private signature. Fail closed.
        return outputProviderId(model).equals(provenance);
    }

    private static String normaliseModelId(String model) {
        return model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Translate OpenAI tool schema ({@code {type:"function", function:{name,description,parameters}}}) into Anthropic
     * shape ({@code {name, description, input_schema}}).
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toApiTools(List<Map<String, Object>> tools, String nativeWebSearchType) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : tools) {
            Map<String, Object> fn = (Map<String, Object>)t.getOrDefault("function", t);
            // Per-provider mutual exclusion: when native web search is on, drop our own web_search tool so the
            // model sees exactly one search tool (the native server tool appended below).
            if (nativeWebSearchType != null && "web_search".equals(fn.get("name"))) {
                continue;
            }
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("name", fn.get("name"));
            if (fn.get("description") != null) {
                a.put("description", fn.get("description"));
            }
            Object params = fn.get("parameters");
            if (params == null) {
                params = Map.of("type", "object", "properties", Map.of());
            }
            a.put("input_schema", params);
            out.add(a);
        }
        // Native Anthropic server-side web search tool (executed on Anthropic's side, returns cited results inline —
        // does NOT come back through our ToolExecutor). Type defaults to the 4.6+ GA value; override for older models.
        if (nativeWebSearchType != null) {
            Map<String, Object> nativeTool = new LinkedHashMap<>();
            nativeTool.put("type", nativeWebSearchType);
            nativeTool.put("name", "web_search");
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

    /**
     * Build the HTTP request. Sends {@code x-api-key} + {@code anthropic-version}; optional {@code anthropic-beta}
     * (from {@link Options#betas()}) and dual idempotency headers. When CC emulation is enabled, injects Claude Code CLI
     * signature headers.
     */
    private Request buildRequest(Map<String, Object> body, String idempotencyKey) {
        String json;
        try {
            json = M.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Request.Builder b = new Request.Builder().url(resolveEndpoint(baseUrl, "/messages")).post(RequestBody.create(json, JSON))
            .addHeader("anthropic-version", API_VERSION).addHeader("content-type", "application/json");
        if (apiKey != null && !apiKey.isBlank()) {
            b.addHeader("x-api-key", apiKey);
        }

        // anthropic-beta: merge user-configured betas with CC-specific betas (if emulation is on)
        List<String> allBetas = new ArrayList<>();
        if (opts.betas() != null) allBetas.addAll(opts.betas());
        if (opts.emulation()) {
            for (String ccBeta : CC_BETAS) {
                if (!allBetas.contains(ccBeta)) allBetas.add(ccBeta);
            }
        }
        if (containsCompaction(body)) allBetas.add(COMPACTION_BETA);
        if (!allBetas.isEmpty()) {
            b.addHeader("anthropic-beta", String.join(",", allBetas));
        }

        // CC emulation headers
        if (opts.emulation()) {
            b.removeHeader("User-Agent");
            b.addHeader("User-Agent", "claude-cli/" + opts.ccVersion() + " (external, cli)");
            b.addHeader("x-app", "cli");
            b.addHeader("X-Claude-Code-Session-Id", sessionId);
            // X-Stainless SDK headers (Anthropic TypeScript SDK 自动添加)
            b.addHeader("X-Stainless-Lang", "js");
            b.addHeader("X-Stainless-OS", "Windows");
            b.addHeader("X-Stainless-Arch", "x64");
            b.addHeader("X-Stainless-Runtime", "node");
            b.addHeader("X-Stainless-Runtime-Version", "v22.14.0");
            b.addHeader("X-Stainless-Package-Version", "0.52.1");
            b.addHeader("X-Stainless-Retry-Count", "0");
        }

        if (Boolean.TRUE.equals(body.get("stream"))) {
            b.addHeader("accept", "text/event-stream");
        }
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            b.addHeader("anthropic-idempotency-key", idempotencyKey);
            b.addHeader("Idempotency-Key", idempotencyKey);
        }
        return b.build();
    }

    private static boolean containsCompaction(Map<String, Object> body) {
        if (body.get("messages") instanceof List<?> messages) for (Object value : messages) {
            if (value instanceof Map<?, ?> message && message.get("content") instanceof List<?> content) {
                for (Object item : content) if (item instanceof Map<?, ?> block && "compaction".equals(block.get("type"))) return true;
            }
        }
        return false;
    }

    // ──── CC Emulation helpers ────────────────────────────────────────────────────────────────────

    /** Build the billing-header system block injected at index 0 when CC emulation is active. */
    private Map<String, Object> buildBillingBlock(ChatRequest request) {
        List<ChatMessage> messages = request.getMessages();
        String model = request.getModel() == null ? defaultModel : request.getModel();
        // Native compaction can remove the first user message. Restore the original CLI prefix from our local
        // receipt instead of deriving a different billing header for the retained thinking on the next request.
        for (ChatMessage message : messages) {
            if (!outputStateProducedHere(message.getResponseOutputProvider(), model) || message.getResponseOutputItems() == null) continue;
            try {
                for (JsonNode block : M.readTree(message.getResponseOutputItems())) {
                    String billing = block.path("billing").asText("");
                    if (AnthropicPrefixState.isReceipt(block.path("type").asText())
                        && billing.startsWith("x-anthropic-billing-header:") && billing.length() <= 512) return textBlock(billing);
                }
            } catch (IOException ignored) { /* Malformed replay is reconstructed without private state below. */ }
        }
        String firstUserMsg = extractFirstUserMessage(messages);
        String ccVer = opts.ccVersion();
        String suffix = computeCcVersionSuffix(firstUserMsg, ccVer);
        String billingText = String.format(
            "x-anthropic-billing-header: cc_version=%s.%s; cc_entrypoint=cli; cch=00000;", ccVer, suffix);
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", billingText);
        return block;
    }

    /**
     * Compute a 3-hex-char suffix derived from the first user message. Uses chars at positions 4, 7, 20 (padded with '0'
     * if shorter) + salt + version, SHA-256'd.
     */
    private static String computeCcVersionSuffix(String firstUserMsg, String ccVersion) {
        if (firstUserMsg == null) firstUserMsg = "";
        StringBuilder chars = new StringBuilder();
        for (int pos : new int[] {4, 7, 20}) {
            chars.append(pos < firstUserMsg.length() ? firstUserMsg.charAt(pos) : '0');
        }
        String input = CC_SALT + chars + ccVersion;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            // Take first 3 hex characters (2 from byte[0] + 1 from byte[1])
            return String.format("%02x", hash[0]).substring(0, 2) + String.format("%02x", hash[1]).substring(0, 1);
        } catch (Exception e) {
            return "000";
        }
    }

    /** Extract the text content of the first user message in the conversation. */
    private static String extractFirstUserMessage(List<ChatMessage> messages) {
        if (messages == null) return "";
        for (ChatMessage m : messages) {
            if ("user".equals(m.getRole()) && m.getContent() != null && !m.getContent().isBlank()) {
                return m.getContent();
            }
        }
        return "";
    }
}
