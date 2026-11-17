package com.harnex.provider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
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

/** OpenAI-compatible streaming provider (OpenAI / DeepSeek / OpenRouter / Ollama / …) — Chat Completions wire. */
@Slf4j
public class OpenAiCompatibleProvider extends AbstractHttpProvider {

    private static final String REASONING_PROVIDER_PREFIX = "openai-compatible:";
    private static final Set<String> SUPPORTED_IMAGE_MIMES =
        Set.of("image/png", "image/jpeg", "image/webp", "image/gif");
    private static final Map<String, String> AUDIO_FORMATS = Map.of(
        "audio/wav", "wav", "audio/x-wav", "wav", "audio/wave", "wav", "audio/vnd.wave", "wav",
        "audio/mpeg", "mp3", "audio/mp3", "mp3");
    private static final Set<String> SUPPORTED_VIDEO_MIMES =
        Set.of("video/mp4", "video/mpeg", "video/mov", "video/avi", "video/x-flv", "video/mpg", "video/webm",
            "video/wmv", "video/3gpp");
    private static final Set<String> SUPPORTED_FILE_MIMES = Set.of("application/pdf", "text/plain", "text/markdown",
        "text/csv", "application/json", "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation", "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final long MAX_INLINE_REQUEST_BYTES = 20L * 1024 * 1024;

    public record Options(boolean emulation, String emulationVersion, String effort, boolean promptCacheKey,
        boolean promptCacheExplicit, String thinking, boolean promptCaching, String reasoningHistory) {
        public static Options defaults() {
            return new Options(false, null, null, false, false, null, false, "auto");
        }

        public Options(boolean emulation, String emulationVersion) {
            this(emulation, emulationVersion, null, false, false, null, false, "auto");
        }

        public Options(boolean emulation, String emulationVersion, String effort, boolean promptCacheKey) {
            this(emulation, emulationVersion, effort, promptCacheKey, false, null, false, "auto");
        }

        public Options(boolean emulation, String emulationVersion, String effort, boolean promptCacheKey,
            boolean promptCacheExplicit) {
            this(emulation, emulationVersion, effort, promptCacheKey, promptCacheExplicit, null, false, "auto");
        }

        public String codexVersion() {
            return (emulationVersion == null || emulationVersion.isBlank()) ? "0.142.5" : emulationVersion;
        }
    }

    /** Vision config (resolved against {@link com.harnex.config.AgentConfig.Vision}). */
    private final boolean visionEnabled;
    /** Lower-case exact model ids explicitly configured with input modality {@code image}. */
    private final Set<String> imageInputModels;
    /** Lower-case exact model ids explicitly configured with input modality {@code file}. */
    private final Set<String> fileInputModels;
    private final long maxImageBytes;
    private final String visionDetail;
    private final int maxImagesPerMessage;
    /** Sandbox dataDir + uploads root used to resolve a thread's upload files for vision inlining. */
    private final Path uploadsRoot;
    private final Options opts;
    private final int defaultMaxTokens;
    private final ProviderModelCapabilities modelCapabilities;
    /** Stable per-provider session ID used for Codex emulation headers. */
    private final String sessionId = UUID.randomUUID().toString();

    /** Plain constructor — used only by tests / one-shot summariser. Vision off, no upload root. */
    public OpenAiCompatibleProvider(String name, String baseUrl, String apiKey, String defaultModel) {
        this(name, baseUrl, apiKey, defaultModel, false, Set.of(), 0L, "auto", 0, null, null, null, null);
    }

    public OpenAiCompatibleProvider(String name, String baseUrl, String apiKey, String defaultModel,
        boolean visionEnabled, long maxImageBytes, String visionDetail, int maxImagesPerMessage, Path uploadsRoot) {
        this(name, baseUrl, apiKey, defaultModel, visionEnabled, Set.of(), maxImageBytes, visionDetail,
            maxImagesPerMessage, uploadsRoot, null, null, null);
    }

    /**
     * @deprecated Pass a {@link ProxyResolution.Resolved} via the 11-arg overload so the OkHttp client honours
     *             {@code harnex.http.proxy} / per-provider {@code proxy} settings. Retained so existing test fixtures
     *             keep compiling; new code paths ({@link ProviderRouter}) always go through the new overload.
     */
    @Deprecated
    public OpenAiCompatibleProvider(String name, String baseUrl, String apiKey, String defaultModel,
        boolean visionEnabled, long maxImageBytes, String visionDetail, int maxImagesPerMessage, Path uploadsRoot,
        HarnexMetrics metrics) {
        this(name, baseUrl, apiKey, defaultModel, visionEnabled, Set.of(), maxImageBytes, visionDetail,
            maxImagesPerMessage, uploadsRoot, metrics, null, null);
    }

    public OpenAiCompatibleProvider(String name, String baseUrl, String apiKey, String defaultModel,
        boolean visionEnabled, long maxImageBytes, String visionDetail, int maxImagesPerMessage, Path uploadsRoot,
        HarnexMetrics metrics, ProxyResolution.Resolved proxy) {
        this(name, baseUrl, apiKey, defaultModel, visionEnabled, Set.of(), maxImageBytes, visionDetail,
            maxImagesPerMessage, uploadsRoot, metrics, proxy, Options.defaults());
    }

    public OpenAiCompatibleProvider(String name, String baseUrl, String apiKey, String defaultModel,
        boolean visionEnabled, long maxImageBytes, String visionDetail, int maxImagesPerMessage, Path uploadsRoot,
        HarnexMetrics metrics, ProxyResolution.Resolved proxy, Options opts) {
        this(name, baseUrl, apiKey, defaultModel, visionEnabled, Set.of(), maxImageBytes, visionDetail,
            maxImagesPerMessage, uploadsRoot, metrics, proxy, opts);
    }

    public OpenAiCompatibleProvider(String name, String baseUrl, String apiKey, String defaultModel,
        HarnexMetrics metrics, ProxyResolution.Resolved proxy, Options opts,
        ProviderModelCapabilities modelCapabilities) {
        this(name, baseUrl, apiKey, defaultModel, false, Set.of(), Set.of(), 0L, "auto", 0, null, metrics, proxy,
            opts, 0, modelCapabilities);
    }

    /**
     * Capability-aware constructor. Image data is sent only when both {@code visionEnabled} and the request's exact
     * model id is present in {@code imageInputModels}; an empty set therefore means "unknown", not "assume vision".
     */
    public OpenAiCompatibleProvider(String name, String baseUrl, String apiKey, String defaultModel,
        boolean visionEnabled, Set<String> imageInputModels, long maxImageBytes, String visionDetail,
        int maxImagesPerMessage, Path uploadsRoot, HarnexMetrics metrics,
        ProxyResolution.Resolved proxy, Options opts) {
        this(name, baseUrl, apiKey, defaultModel, visionEnabled, imageInputModels, Set.of(), maxImageBytes,
            visionDetail, maxImagesPerMessage, uploadsRoot, metrics, proxy, opts);
    }

    /** Capability-aware constructor for Chat Completions image, file, audio, and video content parts. */
    public OpenAiCompatibleProvider(String name, String baseUrl, String apiKey, String defaultModel,
        boolean visionEnabled, Set<String> imageInputModels, Set<String> fileInputModels, long maxAttachmentBytes,
        String visionDetail, int maxAttachmentsPerMessage, Path uploadsRoot,
        HarnexMetrics metrics, ProxyResolution.Resolved proxy, Options opts) {
        this(name, baseUrl, apiKey, defaultModel, visionEnabled, imageInputModels, fileInputModels,
            maxAttachmentBytes, visionDetail, maxAttachmentsPerMessage, uploadsRoot, metrics, proxy, opts, 0,
            ProviderModelCapabilities.conservative("openai-compatible"));
    }

    /** Full constructor used by {@link ProviderRouter}; capabilities are resolved once at startup. */
    public OpenAiCompatibleProvider(String name, String baseUrl, String apiKey, String defaultModel,
        boolean visionEnabled, Set<String> imageInputModels, Set<String> fileInputModels, long maxAttachmentBytes,
        String visionDetail, int maxAttachmentsPerMessage, Path uploadsRoot,
        HarnexMetrics metrics, ProxyResolution.Resolved proxy, Options opts, int defaultMaxTokens,
        ProviderModelCapabilities modelCapabilities) {
        super(name, baseUrl, apiKey, defaultModel, metrics, proxy);
        this.visionEnabled = visionEnabled;
        this.imageInputModels = normaliseModelIds(imageInputModels);
        this.fileInputModels = normaliseModelIds(fileInputModels);
        this.maxImageBytes = maxAttachmentBytes <= 0 ? 10L * 1024 * 1024 : maxAttachmentBytes;
        this.visionDetail = visionDetail == null || visionDetail.isBlank() ? "auto" : visionDetail;
        this.maxImagesPerMessage = Math.max(0, maxAttachmentsPerMessage);
        this.uploadsRoot = uploadsRoot;
        this.opts = opts == null ? Options.defaults() : opts;
        this.defaultMaxTokens = Math.max(0, defaultMaxTokens);
        this.modelCapabilities = modelCapabilities == null
            ? ProviderModelCapabilities.conservative("openai-compatible") : modelCapabilities;
    }

    @Override
    public void streamChat(ChatRequest req, Consumer<StreamEvent> onEvent) {
        streamChat(req, onEvent, 0L);
    }

    @Override
    public void streamChat(ChatRequest req, Consumer<StreamEvent> onEvent, long ttftBudgetMs) {
        String model = req.getModel() == null ? defaultModel : req.getModel();
        ProviderModelCapabilities.Profile modelProfile = requireChatCompletionsModel(model);
        if (!modelProfile.streaming()) {
            throw new ProviderException("config", model + " does not declare streaming support");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        List<Map<String, Object>> apiMessages = toApi(req.getMessages(), model);
        boolean explicitCache = configureExplicitCache(body, apiMessages, modelProfile);
        configureContentCache(apiMessages, modelProfile);
        body.put("messages", apiMessages);
        body.put("stream", true);
        // Ask OpenAI-compatible providers to emit a final {choices:[],usage:{...}} chunk so we can
        // surface token counts in the cockpit-bar. Providers that don't honour this option simply
        // ignore it (we fall back to no usage event).
        if (modelProfile.streamUsage()) {
            body.put("stream_options", Map.of("include_usage", true));
        }
        Integer maxOutputTokens = ProviderCapabilityRequest.applyOutputTokenLimit(body, modelProfile, req.getMaxTokens(), defaultMaxTokens);
        List<Map<String, Object>> apiTools = sanitiseTools(req.getTools());
        boolean hasFunctionTools = apiTools.stream().anyMatch(OpenAiCompatibleProvider::isFunctionTool);
        ProviderCapabilityRequest.requireFunctionTools(modelProfile, !apiTools.isEmpty(), model);
        if (!apiTools.isEmpty()) {
            body.put("tools", apiTools);
        }
        boolean thinkingEnabled = ProviderCapabilityRequest.applyThinking(body, modelProfile, opts.thinking(), model);
        if (req.getTemperature() != null && ProviderCapabilityRequest.sendSampling(modelProfile, thinkingEnabled)) {
            body.put("temperature", req.getTemperature());
        }
        String reasoningEffort = ProviderCapabilityRequest.reasoningEffort(modelProfile,
            req.getReasoningEffort() != null ? req.getReasoningEffort() : opts.effort(),
            hasFunctionTools, model, "reasoning_effort");
        if (reasoningEffort != null) {
            body.put("reasoning_effort", reasoningEffort);
        }
        if (opts.promptCacheKey() && modelProfile.promptCacheKey() || explicitCache) {
            body.put("prompt_cache_key", PromptCacheKeys.forRequest(name, model, req.getPromptCacheScope(),
                req.getMessages(), apiTools));
        }
        ProviderCapabilityRequest.applyReasoningHistory(body, modelProfile, opts.reasoningHistory(), model);
        ProviderCapabilityRequest.applyRequestOptions(body, modelProfile);
        PromptCachePlan.apply(body, req, modelProfile,
            modelProfile.promptCacheExplicit() ? opts.promptCacheExplicit() : opts.promptCaching());

        Request http = buildRequest(body, req.getIdempotencyKey());

        // DEBUG-only full request/response logging, correlated by requestId. Gated on isDebugEnabled
        // so INFO+ stays zero-cost (the body serialisation below only runs under DEBUG).
        String requestId = ProviderStreamLogger.newRequestId();
        ChatStreamDiagnostics diagnostics = new ChatStreamDiagnostics(requestId, name, model, maxOutputTokens);
        long startNanos = System.nanoTime();
        if (log.isDebugEnabled()) {
            ProviderStreamLogger.logRequest(log, requestId, name, http.url().toString(), toJson(body));
        }

        StringBuilder assistantText = new StringBuilder();
        StringBuilder assistantReasoning = new StringBuilder();
        StringBuilder assistantRefusal = new StringBuilder();
        // toolCalls accumulator: index -> partial tool_call
        Map<Integer, Map<String, Object>> partialTools = new TreeMap<>();
        // Tracks the most recent tool_call `index`. Some upstreams (notably Codex-emulating relays)
        // emit `index` only on the opening frame and omit it on arguments-continuation frames; reusing
        // the last active index keeps continuation chunks in the correct slot instead of collapsing
        // everything onto a fixed 0.
        int lastToolCallIndex = 0;
        boolean messageStarted = false;
        boolean messageEnded = false;
        boolean sawDone = false;
        boolean cleanEof = false;
        boolean toolCallSignalSeen = false;
        boolean acceptedTextOnlyDone = false;
        String finishReason = null;
        ProviderException pendingErr = null;
        ProviderTokenUsage lastUsage = null;

        // TTFT watchdog. {@code readTimeout(10min)} only fires after long IDLE periods on an
        // already-open response; a provider that completes TLS but never sends ANY content chunk
        // would otherwise pin the AgentLoop for those 10 minutes BEFORE router failover even
        // gets a chance, contradicting the configured router.total-budget-ms
        // semantics. Schedule a {@code Call.cancel()} TTFT_MS after we hand the request to OkHttp;
        // cancel the watchdog the moment we observe the first content (text / thinking / tool_calls)
        // delta so legitimate long generations are NEVER affected by this guard. We deliberately
        // do NOT cancel on {@code message_start} since that fires when WE see HTTP 200 — the
        // provider could still hang indefinitely after sending headers.
        Call call = newCall(http);
        AtomicBoolean firstContentSeen = new AtomicBoolean(false);
        // Caller (router) may pass a tighter cap when the remaining global failover budget is
        // smaller than our default TTFT. Treat 0/negative as "use default", so existing callers
        // (and the legacy 2-arg overload) use the default 90s first-content budget.
        long ttftMs = ttftBudgetMs > 0 ? Math.min(ttftBudgetMs, STREAM_TTFT_MS) : STREAM_TTFT_MS;
        CallCancellation cancellation = new CallCancellation(call::cancel);
        AtomicBoolean watchdogFired = new AtomicBoolean();
        ScheduledFuture<?> ttftWatchdog = STREAM_TTFT_EXEC.schedule(() -> {
            if (firstContentSeen.get()) {
                return;
            }
            log.warn("Provider {} TTFT watchdog firing after {}ms — cancelling stalled stream", name, ttftMs);
            watchdogFired.set(true);
            call.cancel();
        }, ttftMs, TimeUnit.MILLISECONDS);
        try (Response resp = call.execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                String code = String.valueOf(resp.code());
                String msg = resp.body() != null ? readBoundedErrorBody(resp) : resp.message();
                throw new ProviderException(code, "HTTP " + code + ": " + msg);
            }
            onEvent.accept(StreamEvent.of("message_start", Map.of("provider", name, "model", model)));
            messageStarted = true;

            try (BufferedReader br =
                new BufferedReader(new InputStreamReader(resp.body().byteStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = readBoundedLine(br)) != null) {
                    if (line.isEmpty() || !line.startsWith("data:")) {
                        continue;
                    }
                    String payload = line.substring(5).trim();
                    if (payload.isEmpty()) continue;
                    if ("[DONE]".equals(payload)) {
                        sawDone = true;
                        break;
                    }
                    JsonNode node = M.readTree(payload);
                    diagnostics.frame(node);
                    JsonNode upstreamError = node.path("error");
                    if (upstreamError.isObject()) {
                        throw upstreamError(upstreamError);
                    }
                    // Usage chunk: OpenAI-compat streams send a terminal frame with empty choices and
                    // a populated `usage` object when stream_options.include_usage=true.
                    JsonNode usage = node.path("usage");
                    if (usage.isObject() && !usage.isEmpty()) {
                        // The official stream normally has one terminal usage frame, but several compatible relays
                        // repeat cumulative usage on multiple chunks. Retain the latest authoritative snapshot and
                        // account once at the tail; summing snapshots would double-count billing metrics.
                        lastUsage = ProviderUsage.chat(usage);
                    }
                    JsonNode choices = node.path("choices");
                    if (!choices.isArray() || choices.isEmpty()) {
                        continue;
                    }
                    JsonNode choice = choices.get(0);
                    JsonNode finish = choice.get("finish_reason");
                    if (finish != null && !finish.isNull()) {
                        String nextFinishReason = finish.asText("");
                        if (nextFinishReason.isBlank()) {
                            throw new ProviderException("incomplete", "empty finish_reason from " + name);
                        }
                        if (finishReason != null && !finishReason.equals(nextFinishReason)) {
                            throw new ProviderException("incomplete", "conflicting finish_reason values from " + name
                                + ": " + finishReason + " then " + nextFinishReason);
                        }
                        firstContentSeen.set(true);
                        finishReason = nextFinishReason;
                    }
                    JsonNode delta = choice.path("delta");
                    // Some Chinese providers (DeepSeek-R1, Qwen-2.5-Reasoning, Moonshot k1) emit
                    // chain-of-thought via `delta.reasoning_content` (DeepSeek-style) or
                    // `delta.thinking` (Qwen/Anthropic-style). Surface both as a `thinking_delta`
                    // event so the UI can render a collapsed "thinking…" panel without polluting
                    // assistant content (and without hitting the MAX_ASSISTANT_CHARS cap).
                    JsonNode rThink = delta.path("reasoning_content");
                    if (rThink.isTextual() && !rThink.asText().isEmpty()) {
                        firstContentSeen.set(true);
                        String t = rThink.asText();
                        appendBounded(assistantReasoning, t, MAX_REASONING_CHARS, "reasoning response");
                        onEvent.accept(StreamEvent.of("thinking_delta", Map.of("text", t)));
                    } else {
                        JsonNode rThink2 = delta.path("thinking");
                        if (rThink2.isTextual() && !rThink2.asText().isEmpty()) {
                            firstContentSeen.set(true);
                            String t = rThink2.asText();
                            appendBounded(assistantReasoning, t, MAX_REASONING_CHARS, "reasoning response");
                            onEvent.accept(StreamEvent.of("thinking_delta", Map.of("text", t)));
                        }
                    }
                    JsonNode cText = delta.path("content");
                    if (cText.isTextual() && !cText.asText().isEmpty()) {
                        firstContentSeen.set(true);
                        String t = cText.asText();
                        if (assistantText.length() + t.length() > MAX_ASSISTANT_CHARS) {
                            log.warn("Provider {} exceeded {} chars in one response \u2014 truncating", name,
                                MAX_ASSISTANT_CHARS);
                            // "agent_oversize" is non-failover: switching providers won't help, the
                            // agent itself produced too much text. Keeps the router from looping the
                            // same oversize generation across every fallback.
                            throw new ProviderException("agent_oversize",
                                "response exceeded " + MAX_ASSISTANT_CHARS + " chars");
                        }
                        assistantText.append(t);
                        onEvent.accept(StreamEvent.of("text_delta", Map.of("text", t)));
                    }
                    // OpenAI streams policy/safety refusals separately from normal content. The
                    // public Harnex message model has no refusal field, so surface the text through
                    // the existing content channel while retaining a separate marker that makes the
                    // terminal result non-executable (all partial tool calls are discarded below).
                    JsonNode refusal = delta.path("refusal");
                    if (refusal.isTextual() && !refusal.asText().isEmpty()) {
                        firstContentSeen.set(true);
                        String t = refusal.asText();
                        if (assistantText.length() + t.length() > MAX_ASSISTANT_CHARS) {
                            throw new ProviderException("agent_oversize",
                                "response exceeded " + MAX_ASSISTANT_CHARS + " chars");
                        }
                        assistantRefusal.append(t);
                        assistantText.append(t);
                        onEvent.accept(StreamEvent.of("text_delta", Map.of("text", t)));
                    }
                    JsonNode tcArr = delta.path("tool_calls");
                    // Even legacy or malformed tool deltas must disqualify text-only compatibility.
                    toolCallSignalSeen |= delta.hasNonNull("function_call")
                        || !tcArr.isMissingNode() && !tcArr.isNull() && !(tcArr.isArray() && tcArr.isEmpty());
                    if (tcArr.isArray()) {
                        for (JsonNode tc : tcArr) {
                            // Distinguish a genuinely-absent `index` (reuse the active slot) from an
                            // explicit 0 — `asInt(0)` would conflate the two and misroute continuation
                            // frames from relays that only send `index` on the opening frame.
                            int idx = (tc.has("index") && tc.get("index").isNumber())
                                ? tc.get("index").asInt()
                                : lastToolCallIndex;
                            // Defensive: an upstream that emits a wild `index` (intentionally or
                            // through a corrupt stream) would otherwise let `partialTools`
                            // (TreeMap<Integer,…>) accumulate up to Integer.MAX_VALUE distinct
                            // entries, allocating ~hundreds of bytes each — a cheap OOM vector
                            // when a single bad frame can cost gigabytes. Reject implausibly large
                            // tool_call indexes; no real model emits more than a handful per turn.
                            if (idx < 0 || idx > 255 || partialTools.size() > 256) {
                                throw new ProviderException("agent_oversize", "tool_calls index out of range (idx="
                                    + idx + ", slots=" + partialTools.size() + ")");
                            }
                            firstContentSeen.set(true);
                            lastToolCallIndex = idx;
                            // Pre-seed the slot id with a deterministic synthetic value so that if
                            // the upstream provider never emits a tool_call.id (some OAI-compat
                            // servers omit it for streamed deltas), downstream tool dispatch still
                            // has a non-empty correlation id to thread back through tool_call_id.
                            final int idxFinal = idx;
                            Map<String, Object> slot = partialTools.computeIfAbsent(idx, k -> {
                                Map<String, Object> seed = new LinkedHashMap<>();
                                seed.put("id", "call_" + idxFinal + "_" + UUID.randomUUID().toString().substring(0, 8));
                                seed.put("name", "");
                                seed.put("arguments", new StringBuilder());
                                return seed;
                            });
                            if (tc.hasNonNull("id") && !tc.get("id").asText().isEmpty()) {
                                slot.put("id", tc.get("id").asText());
                            } else if (tc.hasNonNull("call_id") && !tc.get("call_id").asText().isEmpty()) {
                                // Codex-emulating relays may surface the Responses-API `call_id`
                                // instead of the Chat Completions `id`.
                                slot.put("id", tc.get("call_id").asText());
                            }
                            JsonNode fn = tc.path("function");
                            // Name/arguments normally arrive nested under `function` (compliant Chat
                            // Completions). Some Codex-emulating relays translate a Responses-API
                            // function_call item — whose `name`/`arguments` live at the item level —
                            // back into a tool_calls delta WITHOUT re-nesting under `function`, so the
                            // fields appear flattened on the tool_call itself. Accept either shape;
                            // without the flattened fallback the name is never captured and the slot is
                            // dropped downstream ("no function.name").
                            JsonNode nameNode = fn.hasNonNull("name") ? fn.get("name")
                                : (tc.hasNonNull("name") ? tc.get("name") : null);
                            if (nameNode != null && !nameNode.asText().isEmpty()) {
                                slot.put("name", nameNode.asText());
                            }
                            JsonNode argsNode = fn.hasNonNull("arguments") ? fn.get("arguments")
                                : (tc.hasNonNull("arguments") ? tc.get("arguments") : null);
                            if (argsNode != null) {
                                StringBuilder argsSb = (StringBuilder)slot.get("arguments");
                                String chunk = argsNode.asText();
                                if (argsSb.length() + chunk.length() > MAX_TOOL_ARGS_CHARS) {
                                    // Drop the partial tool_call slots so message_end emits an
                                    // empty toolCalls list instead of a half-built call that the
                                    // agent loop would then try (and fail) to dispatch.
                                    partialTools.clear();
                                    throw new ProviderException("agent_oversize",
                                        "tool_call arguments exceeded " + MAX_TOOL_ARGS_CHARS + " chars");
                                }
                                argsSb.append(chunk);
                            }
                        }
                    }
                }
                cleanEof = line == null;
            }
        } catch (SocketTimeoutException ste) {
            pendingErr = ProviderException.fromCause("timeout", ste);
        } catch (InterruptedIOException iie) {
            // OkHttp / Okio can surface a {@code Call.cancel()} as InterruptedIOException
            // depending on which JDK socket primitive was blocked at the moment of cancel
            // (Okio's deadline path explicitly throws this). Without the same TTFT-watchdog
            // discrimination as the generic IOException catch below, a watchdog-driven cancel
            // would be silently mapped to {@code "cancelled"} (NOT in DEFAULT_FAILOVER_CODES,
            // by design — user STOP should not burn fallback tokens) and the documented
            // {@code stream_ttft_timeout} failover would never fire. Check first.
            if (watchdogFired.get() && !cancellation.cancelled() && !firstContentSeen.get()) {
                pendingErr = new ProviderException("stream_ttft_timeout",
                    "no first content from " + name + " within " + ttftMs + "ms");
            } else {
                // Genuine cancellation (AgentCtx.cancel() / user STOP). Restore the interrupt
                // flag so AgentLoop's catch unwinds cleanly; do NOT failover.
                Thread.currentThread().interrupt();
                pendingErr = new ProviderException("cancelled", "agent cancelled");
            }
        } catch (JsonProcessingException jpe) {
            // Malformed SSE chunk: do NOT trigger provider failover — just abort this stream.
            pendingErr = ProviderException.fromCause("parse", jpe);
        } catch (ProviderException pe) {
            // HTTP error or oversize — propagate as-is after we close out the message frame.
            pendingErr = pe;
        } catch (IOException e) {
            // Distinguish a TTFT-watchdog cancel from a generic socket timeout so the router
            // surfaces a clearer "stream_ttft_timeout" rather than a vague "timeout". Code
            // remains in the failover-eligible set so the next provider DOES get tried.
            if (cancellation.cancelled()) {
                pendingErr = new ProviderException("cancelled", "run cancelled");
            } else if (watchdogFired.get() && !firstContentSeen.get()) {
                pendingErr = new ProviderException("stream_ttft_timeout",
                    "no first content from " + name + " within " + ttftMs + "ms");
            } else if (e instanceof SocketTimeoutException) {
                // A genuine idle-read timeout — "timeout" is the accurate classification. (Usually
                // caught by the dedicated SocketTimeoutException branch above; kept here as a guard
                // for any subclass that slips through the generic IOException path.)
                pendingErr = ProviderException.fromCause("timeout", e);
            } else if (isStreamReset(e)) {
                // HTTP/2 stream reset / peer-initiated abort (RST_STREAM / INTERNAL_ERROR). This is a
                // transport failure, NOT a read timeout — mislabelling it "timeout" corrupts metrics
                // and misleads incident triage. "network" is still in DEFAULT_FAILOVER_CODES so the
                // next provider is tried.
                pendingErr = ProviderException.fromCause("network", e);
            } else {
                // Any other IOException (connection reset, EOF, broken pipe) is a transport failure —
                // "network" is more accurate than the old blanket "timeout". Preserve the original
                // message for diagnosis.
                pendingErr = ProviderException.fromCause("network", e);
            }
        } finally {
            ttftWatchdog.cancel(false);
            cancellation.close();
        }

        if (firstContentSeen.get()) call.request().tag(HttpExchangeState.class).outputReceived();
        ProviderCallScope.exchange(call.request().tag(HttpExchangeState.class));

        if (pendingErr == null) {
            // Ordinary conversations may accept text plus an explicit end-of-stream sentinel from
            // compatible relays. Keep the upstream reason missing, and never extend this to tools,
            // explicit errors, reasoning-only output, EOF, or a known exhausted output budget.
            if (req.isAllowTextOnlyDone() && finishReason == null && sawDone && !cancellation.cancelled()
                && !assistantText.toString().isBlank() && assistantRefusal.isEmpty()
                && !toolCallSignalSeen && partialTools.isEmpty()) {
                if (maxOutputTokens != null && lastUsage != null && lastUsage.completion() >= maxOutputTokens) {
                    pendingErr = ProviderException.generationLimit(maxOutputTokens, lastUsage);
                } else {
                    acceptedTextOnlyDone = true;
                }
            } else if (finishReason == null && sawDone && assistantText.toString().isBlank()
                && assistantReasoning.toString().isBlank() && partialTools.isEmpty()) {
                pendingErr = new ProviderException("incomplete",
                    "Provider returned no answer content before [DONE] (no finish_reason); upstream returned an empty response");
            } else {
                pendingErr = terminalError(finishReason, sawDone, assistantRefusal, partialTools, maxOutputTokens, lastUsage);
            }
        }

        if (lastUsage != null) {
            Map<String, Object> u = new LinkedHashMap<>(lastUsage.eventData());
            u.put("model", req.getModel() == null ? defaultModel : req.getModel());
            u.put("provider", name);
            onEvent.accept(StreamEvent.of("usage", u));
            lastUsage.record(metrics);
        }

        // Only a successful tool_calls completion may produce executable calls. Validate every
        // slot before exposing any of them; otherwise a clean finish with truncated JSON (or one
        // missing function.name) would be dispatched as if it were complete.
        List<ToolCall> toolCalls = new ArrayList<>();
        if (pendingErr == null && assistantRefusal.isEmpty() && "tool_calls".equals(finishReason)) {
            for (var entry : partialTools.entrySet()) {
                Map<String, Object> s = entry.getValue();
                String tname = String.valueOf(s.get("name"));
                if (tname == null || tname.isBlank() || "null".equals(tname)) {
                    pendingErr = new ProviderException("incomplete",
                        "tool_call index " + entry.getKey() + " ended without function.name");
                    break;
                }
                String toolId = String.valueOf(s.get("id"));
                if (toolId.isBlank() || "null".equals(toolId)) {
                    pendingErr = new ProviderException("incomplete",
                        "tool_call index " + entry.getKey() + " ended without id");
                    break;
                }
                String args = s.get("arguments").toString();
                if (args.isEmpty()) {
                    args = "{}";
                }
                try {
                    JsonNode arguments = M.reader()
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .readTree(args);
                    if (arguments == null || !arguments.isObject()) {
                        throw new IllegalArgumentException("tool arguments must be a JSON object");
                    }
                } catch (Exception jpe) {
                    pendingErr = new ProviderException("incomplete", "tool_call index " + entry.getKey()
                        + " (name=" + tname + ") ended with invalid arguments: " + jpe.getMessage());
                    break;
                }
                toolCalls.add(ToolCall.builder().id(toolId).name(tname).arguments(args).build());
            }
            if (pendingErr != null) {
                toolCalls.clear();
            }
        }
        diagnostics.finish(log, call.request().tag(HttpExchangeState.class), finishReason,
            sawDone, cleanEof, assistantText.length(), partialTools.size(), lastUsage, pendingErr, acceptedTextOnlyDone);
        // Always emit message_end once message_start fired — even on mid-stream failure — so AgentLoop
        // can persist whatever the user already saw and the SSE consumer never hangs waiting for a
        // terminal frame that never comes.
        if (messageStarted && !messageEnded) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("content", assistantText.toString());
            payload.put("toolCalls", toolCalls);
            if (!assistantReasoning.isEmpty()) {
                payload.put("reasoning", assistantReasoning.toString());
                // Plain-text reasoning still needs provenance: without it a later failover could replay one vendor's
                // reasoning_content to a different OpenAI-compatible provider that happens to enable replay.
                // Partial/error reasoning is visible for diagnosis but is not valid continuation state.
                if (pendingErr == null && !acceptedTextOnlyDone) {
                    payload.put("reasoningProvider", reasoningProviderId(model));
                }
            }
            if (!assistantRefusal.isEmpty()) {
                payload.put("refusal", assistantRefusal.toString());
            }
            if (finishReason != null) {
                payload.put("stopReason", finishReason);
            }
            if (acceptedTextOnlyDone) {
                // Persist the compatibility decision without inventing an upstream stop reason.
                payload.put("completionSource", "done_sentinel");
            }
            if (pendingErr != null) {
                payload.put("interrupted", true);
                payload.put("error", pendingErr.code);
            }
            onEvent.accept(StreamEvent.of("message_end", payload));
            messageEnded = true;
        }
        if (log.isDebugEnabled()) {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            boolean interrupted = pendingErr != null;
            String stopReason = pendingErr != null ? pendingErr.code : finishReason;
            ProviderStreamLogger.logResponse(log, requestId, name, assistantText.toString(),
                assistantReasoning.toString(), toolCalls, stopReason, interrupted, durationMs);
        }
        if (pendingErr != null) {
            throw withExchange(pendingErr, call);
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
        return oneShot(ChatRequest.builder().model(model).messages(messages).temperature(temperature).maxTokens(maxOutputTokens).build(), timeoutMs);
    }

    @Override public OneShotResult oneShot(ChatRequest utility, long timeoutMs) {
        String model = utility.getModel();
        List<ChatMessage> messages = utility.getMessages();
        Float temperature = utility.getTemperature();
        Integer maxOutputTokens = utility.getMaxTokens();
        String effectiveModel = model == null ? defaultModel : model;
        ProviderModelCapabilities.Profile modelProfile = requireChatCompletionsModel(effectiveModel);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", effectiveModel);
        List<Map<String, Object>> apiMessages = toApi(messages, effectiveModel);
        boolean explicitCache = configureExplicitCache(body, apiMessages, modelProfile);
        configureContentCache(apiMessages, modelProfile);
        body.put("messages", apiMessages);
        body.put("stream", false);
        ProviderCapabilityRequest.applyOutputTokenLimit(body, modelProfile, maxOutputTokens, defaultMaxTokens);
        List<Map<String, Object>> apiTools = sanitiseTools(utility.getTools());
        boolean hasFunctionTools = apiTools.stream().anyMatch(OpenAiCompatibleProvider::isFunctionTool);
        ProviderCapabilityRequest.requireFunctionTools(modelProfile, !apiTools.isEmpty(), effectiveModel);
        if (!apiTools.isEmpty()) body.put("tools", apiTools);
        boolean thinkingEnabled =
            ProviderCapabilityRequest.applyThinking(body, modelProfile, opts.thinking(), effectiveModel);
        if (temperature != null && ProviderCapabilityRequest.sendSampling(modelProfile, thinkingEnabled)) {
            body.put("temperature", temperature);
        }
        String reasoningEffort = ProviderCapabilityRequest.reasoningEffort(modelProfile,
            utility.getReasoningEffort() != null ? utility.getReasoningEffort() : opts.effort(),
            hasFunctionTools, effectiveModel, "reasoning_effort");
        if (reasoningEffort != null) {
            body.put("reasoning_effort", reasoningEffort);
        }
        if (opts.promptCacheKey() && modelProfile.promptCacheKey() || explicitCache) {
            body.put("prompt_cache_key", PromptCacheKeys.forRequest(name, effectiveModel, utility.getPromptCacheScope(), messages, apiTools));
        }
        ProviderCapabilityRequest.applyReasoningHistory(body, modelProfile, opts.reasoningHistory(), effectiveModel);
        ProviderCapabilityRequest.applyRequestOptions(body, modelProfile);
        PromptCachePlan.apply(body, utility, modelProfile,
            modelProfile.promptCacheExplicit() ? opts.promptCacheExplicit() : opts.promptCaching());
        Request req = buildRequest(body);
        // Hold the Call so the timeout watchdog can {@code call.cancel()} — that is the ONLY
        // reliable way to stop an in-flight OkHttp request (interrupt-based cancellation does
        // nothing, and our pool reuses worker threads). On cancel, OkHttp throws IOException
        // out of {@code execute()} and the {@code try-with-resources} releases the response
        // body so the worker is freed instead of staying blocked until the 10-minute idle
        // readTimeout pop. Schedule via a tiny shared 1-thread executor — one daemon thread
        // is enough to manage thousands of overlapping cancels (it just calls .cancel()).
        Call call = newCall(req);
        return runWithTimeout(call, timeoutMs, resp -> {
            JsonNode json = M.readTree(readBoundedSuccessBody(resp));
            JsonNode usage = json.path("usage");
            ProviderTokenUsage tokenUsage = usage.isObject() && !usage.isEmpty()
                ? ProviderUsage.chat(usage) : null;
            if (tokenUsage != null) tokenUsage.record(metrics);
            JsonNode upstreamError = json.path("error");
            if (upstreamError.isObject()) {
                throw upstreamError(upstreamError).withUsage(tokenUsage);
            }
            JsonNode choices = json.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new ProviderException("incomplete", "oneShot response contained no choices").withUsage(tokenUsage);
            }
            JsonNode choice = choices.get(0);
            JsonNode message = choice.path("message");
            String refusal = message.path("refusal").asText("");
            String finishReason = choice.path("finish_reason").asText("");
            switch (finishReason) {
                case "stop":
                    return new OneShotResult(refusal.isEmpty() ? message.path("content").asText("") : refusal,
                        name, effectiveModel, tokenUsage);
                case "length":
                    throw ProviderException.generationLimit(effectiveMaxTokens(modelProfile, maxOutputTokens), tokenUsage);
                case "content_filter":
                    throw new ProviderException("content_filter", "oneShot ended with finish_reason=content_filter").withUsage(tokenUsage);
                case "insufficient_system_resource":
                    throw new ProviderException("503",
                        "oneShot ended with finish_reason=insufficient_system_resource").withUsage(tokenUsage);
                case "tool_calls", "function_call":
                    throw new ProviderException("incomplete",
                        "oneShot cannot represent finish_reason=" + finishReason).withUsage(tokenUsage);
                default:
                    throw new ProviderException("incomplete", finishReason.isBlank()
                        ? "oneShot response contained no finish_reason"
                        : "oneShot ended with unknown finish_reason=" + finishReason).withUsage(tokenUsage);
            }
        });
    }

    private ProviderModelCapabilities.Profile requireChatCompletionsModel(String model) {
        ProviderModelCapabilities.Profile profile = modelCapabilities.profile(model);
        if (!profile.chatCompletionsApi()) {
            throw new ProviderException("config", model
                + " is not exposed through Chat Completions; configure provider type openai-responses instead");
        }
        return profile;
    }

    /** Apply an OpenAI explicit stable-prefix breakpoint only when both intent and model capability are declared. */
    @SuppressWarnings("unchecked")
    private boolean configureExplicitCache(Map<String, Object> body, List<Map<String, Object>> messages,
        ProviderModelCapabilities.Profile profile) {
        if (!opts.promptCacheExplicit() || !profile.promptCacheExplicit()) {
            return false;
        }
        if (!opts.promptCacheKey()) {
            throw new ProviderException("config", "prompt-cache-explicit requires prompt-cache-key=true");
        }
        Map<String, Object> lastInstruction = null;
        for (Map<String, Object> message : messages) {
            if (!"system".equals(message.get("role")) && !"developer".equals(message.get("role"))) {
                break;
            }
            lastInstruction = message;
        }
        if (lastInstruction == null) {
            return false;
        }
        Object content = lastInstruction.get("content");
        List<Map<String, Object>> blocks = new ArrayList<>();
        if (content instanceof String text) {
            blocks.add(new LinkedHashMap<>(Map.of("type", "text", "text", text)));
        } else if (content instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> block) {
                    blocks.add(new LinkedHashMap<>((Map<String, Object>)block));
                }
            }
        }
        if (blocks.isEmpty()) {
            return false;
        }
        blocks.get(blocks.size() - 1).put("prompt_cache_breakpoint", Map.of("mode", "explicit"));
        lastInstruction.put("content", blocks);
        body.put("prompt_cache_options", Map.of("mode", "explicit"));
        return true;
    }

    /** Qwen/DashScope-style content cache breakpoint on the final stable leading instruction block. */
    @SuppressWarnings("unchecked")
    private void configureContentCache(List<Map<String, Object>> messages,
        ProviderModelCapabilities.Profile profile) {
        if (!opts.promptCaching()
            || profile.promptCacheMode() != ProviderModelCapabilities.PromptCacheMode.CONTENT_CACHE_CONTROL) {
            return;
        }
        Map<String, Object> lastInstruction = null;
        for (Map<String, Object> message : messages) {
            Object role = message.get("role");
            if (!"system".equals(role) && !"developer".equals(role)) {
                break;
            }
            lastInstruction = message;
        }
        if (lastInstruction == null) {
            return;
        }
        Object content = lastInstruction.get("content");
        List<Map<String, Object>> blocks = new ArrayList<>();
        if (content instanceof String text) {
            blocks.add(new LinkedHashMap<>(Map.of("type", "text", "text", text)));
        } else if (content instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> block) {
                    blocks.add(new LinkedHashMap<>((Map<String, Object>)block));
                }
            }
        }
        if (!blocks.isEmpty()) {
            blocks.get(blocks.size() - 1).put("cache_control", Map.of("type", "ephemeral"));
            lastInstruction.put("content", blocks);
        }
    }

    /** Remove Harnex-only execution metadata from the public Chat Completions tool schema. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> sanitiseTools(List<Map<String, Object>> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(tools.size());
        for (Map<String, Object> tool : tools) {
            if (tool == null) {
                continue;
            }
            Map<String, Object> cleanTool = new LinkedHashMap<>(tool);
            Object function = cleanTool.get("function");
            if (function instanceof Map<?, ?> rawFunction) {
                Map<String, Object> cleanFunction = new LinkedHashMap<>((Map<String, Object>)rawFunction);
                cleanFunction.remove("x-capabilities");
                cleanFunction.remove("x-timeoutMs");
                cleanTool.put("function", cleanFunction);
            }
            out.add(cleanTool);
        }
        return out;
    }

    private static boolean isFunctionTool(Map<String, Object> tool) {
        if (tool == null) {
            return false;
        }
        Object type = tool.get("type");
        return type == null ? tool.get("function") instanceof Map<?, ?> : "function".equals(type);
    }

    private ProviderException terminalError(String finishReason, boolean sawDone, StringBuilder refusal,
        Map<Integer, Map<String, Object>> partialTools, Integer requestedLimit, ProviderTokenUsage usage) {
        if (finishReason == null || finishReason.isBlank()) {
            return new ProviderException("incomplete", sawDone
                ? "stream sent [DONE] without finish_reason; response completeness cannot be confirmed"
                : "stream ended without finish_reason; response may be truncated");
        }
        return switch (finishReason) {
            case "stop" -> !refusal.isEmpty() || partialTools.isEmpty() ? null
                : new ProviderException("incomplete", "finish_reason=stop with an unfinished tool_call");
            case "tool_calls" -> !refusal.isEmpty() ? null : partialTools.isEmpty()
                ? new ProviderException("incomplete", "finish_reason=tool_calls without a tool_call")
                : null;
            case "length" -> ProviderException.generationLimit(requestedLimit, usage);
            case "content_filter" ->
                new ProviderException("content_filter", "stream ended with finish_reason=content_filter");
            case "insufficient_system_resource" ->
                new ProviderException("503", "stream ended with finish_reason=insufficient_system_resource");
            case "function_call" ->
                new ProviderException("incomplete", "legacy finish_reason=function_call is not supported");
            default -> new ProviderException("incomplete", "stream ended with unknown finish_reason=" + finishReason);
        };
    }

    private static ProviderException upstreamError(JsonNode error) {
        String code = error.path("code").asText("");
        if (code.isBlank()) {
            code = error.path("type").asText("upstream_error");
        }
        if (code.isBlank()) {
            code = "upstream_error";
        }
        return new ProviderException(code, error.path("message").asText("upstream error"));
    }

    private Request buildRequest(Map<String, Object> body) {
        return buildRequest(body, null);
    }

    /**
     * Build the HTTP request, optionally attaching an {@code Idempotency-Key} header so OpenAI- compatible upstreams
     * can de-duplicate when the failover layer retries a non-idempotent POST whose response was lost in flight
     * (preventing duplicate billing / duplicate tool execution). The header is only emitted when the key is non-blank —
     * leaving the existing one-shot / legacy code paths untouched.
     */
    private Request buildRequest(Map<String, Object> body, String idempotencyKey) {
        String json;
        try {
            json = M.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Request.Builder b =
            new Request.Builder().url(resolveEndpoint(baseUrl, "/chat/completions")).post(RequestBody.create(json, JSON));
        if (apiKey != null && !apiKey.isBlank()) {
            b.addHeader("Authorization", "Bearer " + apiKey);
        }
        b.addHeader("Accept", "text/event-stream");
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            b.addHeader("Idempotency-Key", idempotencyKey);
        }
        // Codex CLI emulation headers
        if (opts.emulation()) {
            applyEmulationHeaders(b);
        }

        // Full (masked) request logging is handled centrally by ProviderStreamLogger.logRequest on the
        // streamChat path — no emulation-specific DEBUG block here, so a single request is only logged
        // once and API keys are never printed in plaintext.
        return b.build();
    }

    /**
     * Apply Codex CLI emulation headers to the given request builder for Chat Completions endpoint.
     * Mirrors {@link OpenAiResponsesProvider#applyEmulationHeaders} but targets the chat/completions wire.
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

    private List<Map<String, Object>> toApi(List<ChatMessage> msgs, String model) {
        List<Map<String, Object>> out = new ArrayList<>();
        InlineBudget inlineBudget = new InlineBudget(MAX_INLINE_REQUEST_BYTES, maxImagesPerMessage);
        Map<ChatMessage, Object> preparedUserContent = new IdentityHashMap<>();
        // Reserve the bounded native-attachment envelope from newest to oldest while preserving message order below.
        // The current user turn should not lose its attachment merely because an old history item consumed the quota.
        for (int i = msgs.size() - 1; i >= 0; i--) {
            ChatMessage message = msgs.get(i);
            if (message != null && "user".equals(message.getRole()) && message.getAttachments() != null
                && !message.getAttachments().isEmpty()) {
                preparedUserContent.put(message, buildContent(message, model, inlineBudget));
            }
        }
        ProviderModelCapabilities.Profile profile = modelCapabilities.profile(model);
        ProviderModelCapabilities.ReasoningReplay replay =
            ProviderCapabilityRequest.reasoningReplay(profile, opts.reasoningHistory(), model);
        boolean replayReasoning = replay == ProviderModelCapabilities.ReasoningReplay.ALL
            || replay == ProviderModelCapabilities.ReasoningReplay.TOOL_CALLS;
        boolean developerInstructions = "developer".equals(profile.instructionRole());
        String pendingReasoning = null;
        for (ChatMessage m : msgs) {
            if (m.isReasoningBlock()) {
                // A reasoning block is an internal timeline node, not an independent API turn. Providers that
                // require reasoning history (for example Kimi) expect it on the assistant message that produced
                // the content/tool calls, so retain adjacent block(s) and merge them into that message below.
                // When replay is disabled the block remains UI-only and is deliberately stripped.
                if (replayReasoning && reasoningProducedHere(m, model) && m.getReasoning() != null
                    && !m.getReasoning().isBlank()) {
                    pendingReasoning = pendingReasoning == null ? m.getReasoning()
                        : pendingReasoning + "\n" + m.getReasoning();
                }
                continue;
            }
            Map<String, Object> j = new LinkedHashMap<>();
            // Current official reasoning models use developer for application instructions. Unknown compatible
            // relays retain system because their validators may predate the newer role.
            String apiRole = developerInstructions && "system".equals(m.getRole()) ? "developer" : m.getRole();
            j.put("role", apiRole);
            boolean hasToolCalls = m.getToolCalls() != null && !m.getToolCalls().isEmpty();
            // Native attachments: emit official image_url/file content parts only when the global gate and exact
            // endpoint capability are enabled. Unknown capabilities receive a visible file_read/OCR hint rather than
            // a field the upstream may reject.
            Object content = preparedUserContent.containsKey(m) ? preparedUserContent.get(m)
                : buildContent(m, model, inlineBudget);
            j.put("content", content);
            // tool_call_id is only valid on role="tool" responses per the OpenAI tool-calling
            // spec. Sending it on assistant/user messages tightens some providers' validators
            // (and at minimum confuses logging).
            if ("tool".equals(m.getRole()) && m.getToolCallId() != null) {
                j.put("tool_call_id", m.getToolCallId());
            }
            boolean replayThisAssistant = replay == ProviderModelCapabilities.ReasoningReplay.ALL
                || replay == ProviderModelCapabilities.ReasoningReplay.TOOL_CALLS && hasToolCalls;
            if ("assistant".equals(m.getRole()) && replayThisAssistant) {
                // Same-message reasoning is replayed only when it carries the exact provider/model provenance tag.
                // Never fabricate reasoning_content for foreign or untagged history: Kimi requires the complete
                // original value, and an empty compatibility value is not an equivalent continuation state.
                String reasoning = reasoningProducedHere(m, model) ? m.getReasoning() : null;
                if ((reasoning == null || reasoning.isBlank()) && pendingReasoning != null) {
                    reasoning = pendingReasoning;
                }
                if (reasoning != null && !reasoning.isBlank()) {
                    j.put("reasoning_content", reasoning);
                }
            }
            if (hasToolCalls) {
                List<Map<String, Object>> tcs = new ArrayList<>();
                for (ToolCall tc : m.getToolCalls()) {
                    // Map.of() rejects null keys / values with NullPointerException — historical
                    // ToolCall entries (mid-stream interrupt, MAX_TOOL_ARGS_CHARS truncation,
                    // older serialised threads, externally-imported transcripts) can have
                    // null id / name / arguments. Tolerate them with synthesised placeholders
                    // so the whole replay isn't bricked. AnthropicProvider already degrades
                    // gracefully on malformed args; mirror that policy here.
                    String id = tc.getId();
                    String name = tc.getName();
                    String args = tc.getArguments();
                    if (id == null || id.isBlank()) {
                        id = "call_unknown_" + Integer.toHexString(System.identityHashCode(tc));
                        log.warn(
                            "OpenAiCompatibleProvider: historical tool_call has null id, synthesised placeholder '{}' (name={})",
                            id, name);
                    }
                    if (name == null || name.isBlank()) {
                        log.warn(
                            "OpenAiCompatibleProvider: historical tool_call id={} has null name, using 'unknown_tool'",
                            id);
                        name = "unknown_tool";
                    }
                    if (args == null || args.isBlank()) {
                        args = "{}";
                    }
                    Map<String, Object> fn = new LinkedHashMap<>();
                    fn.put("name", name);
                    fn.put("arguments", args);
                    Map<String, Object> tcMap = new LinkedHashMap<>();
                    tcMap.put("id", id);
                    tcMap.put("type", "function");
                    tcMap.put("function", fn);
                    tcs.add(tcMap);
                }
                j.put("tool_calls", tcs);
            }
            out.add(j);
            // A standalone reasoning block belongs only to the immediately-following real assistant message.
            // Never let it cross a user/system/tool boundary or attach to a later turn.
            pendingReasoning = null;
        }
        return out;
    }

    private Integer effectiveMaxTokens(ProviderModelCapabilities.Profile profile, Integer requestOverride) {
        return ProviderCapabilityRequest.outputTokenLimit(profile, requestOverride, defaultMaxTokens);
    }

    private String reasoningProviderId(String model) {
        return REASONING_PROVIDER_PREFIX + name + ":" + normaliseModelId(model);
    }

    private boolean reasoningProducedHere(ChatMessage message, String model) {
        return reasoningProviderId(model).equals(message.getReasoningProvider());
    }

    private static String normaliseModelId(String model) {
        return model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Build the {@code content} field for one message. Returns a String for normal messages, or a
     * {@code List<Map<String,Object>>} typed array when native image/file/audio/video attachments or visible fallbacks are
     * present.
     */
    private Object buildContent(ChatMessage m, String model, InlineBudget inlineBudget) {
        String text = m.getContent() == null ? "" : m.getContent();
        List<MessageAttachment> atts = m.getAttachments();
        if (!"user".equals(m.getRole()) || atts == null || atts.isEmpty()) {
            return text;
        }
        List<Map<String, Object>> parts = new ArrayList<>();
        if (!text.isEmpty()) {
            parts.add(Map.of("type", "text", "text", text));
        }
        boolean supportsImages = supportsImageInput(model);
        boolean supportsFiles = supportsFileInput(model);
        boolean supportsVideo = supportsVideoInput(model);
        ProviderModelCapabilities.Profile profile = modelCapabilities.profile(model);
        boolean supportsAudio = profile.exactModel() && profile.supportsModality("audio");
        int imgCount = 0;
        for (MessageAttachment att : atts) {
            if (att == null) {
                parts.add(textPart("[an attachment was not sent inline because its metadata is missing]"));
                continue;
            }
            String name = AttachmentPayloadLoader.displayName(att);
            String location = AttachmentPayloadLoader.visiblePath(att, uploadsRoot);
            String mime = att.getMime() == null ? "" : att.getMime().trim().toLowerCase(Locale.ROOT);
            if (mime.startsWith("audio/")) {
                String format = AUDIO_FORMATS.get(mime);
                if (!visionEnabled || !supportsAudio || format == null) {
                    parts.add(textPart("[audio " + name + " is available at " + location
                        + "; it was not sent inline (model/configuration or MIME does not support audio input); use "
                        + "file_read to inspect it]"));
                    continue;
                }
                AttachmentPayloadLoader.Loaded loaded = loadAttachment(att, inlineBudget);
                if (loaded == null) {
                    parts.add(textPart("[audio " + name
                        + " was not sent inline because it is unsafe, unreadable, or exceeds the request limit; use "
                        + "file_read on " + location + "]"));
                    continue;
                }
                String data = Base64.getEncoder().encodeToString(loaded.bytes());
                if (profile.audioInputEncoding() == ProviderModelCapabilities.AudioInputEncoding.DATA_URL) {
                    data = "data:;base64," + data;
                }
                parts.add(Map.of("type", "input_audio", "input_audio", Map.of("data", data, "format", format)));
                continue;
            }
            if (mime.startsWith("video/")) {
                if (!visionEnabled || !supportsVideo || !SUPPORTED_VIDEO_MIMES.contains(mime)) {
                    parts.add(textPart("[video " + name + " is available at " + location
                        + "; it was not sent inline (model/configuration or MIME does not support video input); use "
                        + "file_read to inspect it]"));
                    continue;
                }
                AttachmentPayloadLoader.Loaded loaded = loadAttachment(att, inlineBudget);
                if (loaded == null) {
                    parts.add(textPart("[video " + name
                        + " was not sent inline because it is unsafe, unreadable, or exceeds the request limit; use "
                        + "file_read on " + location + "]"));
                    continue;
                }
                String dataUrl = "data:" + mime + ";base64,"
                    + Base64.getEncoder().encodeToString(loaded.bytes());
                parts.add(Map.of("type", "video_url", "video_url", Map.of("url", dataUrl)));
                continue;
            }
            if (!mime.startsWith("image/")) {
                if (!visionEnabled || !supportsFiles || !SUPPORTED_FILE_MIMES.contains(mime)) {
                    parts.add(textPart("[attached file " + name + " is available at " + location
                        + "; it was not sent inline (model/configuration or MIME does not support file input); use "
                        + "file_read to inspect it]"));
                    continue;
                }
                AttachmentPayloadLoader.Loaded loaded = loadAttachment(att, inlineBudget);
                if (loaded == null) {
                    parts.add(textPart("[attached file " + name
                        + " was not sent inline because it is unsafe, unreadable, or exceeds the request limit; use "
                        + "file_read on " + location + "]"));
                    continue;
                }
                Map<String, Object> file = new LinkedHashMap<>();
                file.put("filename", loaded.name());
                file.put("file_data", "data:" + mime + ";base64,"
                    + Base64.getEncoder().encodeToString(loaded.bytes()));
                parts.add(Map.of("type", "file", "file", file));
                continue;
            }
            if (!visionEnabled) {
                parts.add(textPart("[image " + name
                    + " was not sent inline because vision is disabled; use file_read/OCR on " + location
                    + "]"));
                continue;
            }
            if (!supportsImages) {
                parts.add(textPart("[image " + name + " was not sent inline because model " + model
                    + " is not configured with input modality image; use file_read/OCR on " + location
                    + "]"));
                continue;
            }
            if (!SUPPORTED_IMAGE_MIMES.contains(mime)) {
                parts.add(textPart("[image " + name + " was not sent inline: unsupported MIME " + mime
                    + "; use file_read/OCR on " + location + "]"));
                continue;
            }
            if (imgCount >= maxImagesPerMessage) {
                parts.add(textPart("[image " + name + " omitted: per-message limit " + maxImagesPerMessage
                    + " reached; use file_read/OCR on " + location + "]"));
                continue;
            }
            String dataUrl = inlineImage(att, mime, inlineBudget);
            if (dataUrl == null) {
                parts.add(textPart("[image " + name
                    + " skipped: too large, unsafe, or unreadable; use file_read/OCR on " + location + "]"));
                continue;
            }
            parts.add(Map.of("type", "image_url", "image_url", Map.of("url", dataUrl, "detail", visionDetail)));
            imgCount++;
        }
        if (parts.isEmpty()) {
            return text; // no images survived; fall back to plain text
        }
        // If there's no text part but we added images, prepend an empty text to satisfy
        // strict providers that require at least one text segment in user messages.
        if (parts.stream().noneMatch(p -> "text".equals(p.get("type")))) {
            parts.add(0, Map.of("type", "text", "text", ""));
        }
        return parts;
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

    private boolean supportsVideoInput(String model) {
        ProviderModelCapabilities.Profile profile = modelCapabilities.profile(model);
        return profile.exactModel() && profile.supportsModality("video");
    }

    private static Set<String> normaliseModelIds(Set<String> models) {
        return models == null ? Set.of()
            : models.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    private static Map<String, Object> textPart(String text) {
        return Map.of("type", "text", "text", text == null ? "" : text);
    }

    /** Read a validated upload as {@code data:<mime>;base64,...}, or null if unsafe, too large, or unreadable. */
    private String inlineImage(MessageAttachment att, String mime, InlineBudget inlineBudget) {
        AttachmentPayloadLoader.Loaded loaded = loadAttachment(att, inlineBudget);
        if (loaded == null) {
            return null;
        }
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(loaded.bytes());
    }

    private AttachmentPayloadLoader.Loaded loadAttachment(MessageAttachment attachment, InlineBudget inlineBudget) {
        if (!inlineBudget.canAcceptAnother()) {
            return null;
        }
        long readLimit = Math.min(maxImageBytes, inlineBudget.remainingBytes());
        AttachmentPayloadLoader.Loaded loaded = AttachmentPayloadLoader.load(attachment, uploadsRoot, readLimit);
        return loaded == null || !inlineBudget.consume(loaded.bytes().length) ? null : loaded;
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

    /**
     * Heuristic for an HTTP/2 stream-reset / peer-abort surfaced as a plain {@link IOException}. OkHttp's
     * {@code okhttp3.internal.http2.StreamResetException} is an INTERNAL API, so instead of importing that unstable
     * type we match on the class-name string AND on the human-readable message text ("stream was reset", "RST_",
     * "INTERNAL_ERROR", case-insensitive) as a version-independent fallback — so a class-path change across okhttp
     * versions cannot cause a missed classification.
     */
    private static boolean isStreamReset(IOException e) {
        if (e == null) {
            return false;
        }
        if (e.getClass().getName().contains("StreamResetException")) {
            return true;
        }
        String msg = e.getMessage();
        if (msg == null) {
            return false;
        }
        String m = msg.toLowerCase(Locale.ROOT);
        return m.contains("stream was reset") || m.contains("rst_") || m.contains("internal_error");
    }

    /** Serialise a request body to JSON for DEBUG logging; never throws (falls back to toString on error). */
    private static String toJson(Object body) {
        try {
            return M.writeValueAsString(body);
        } catch (Exception e) {
            return String.valueOf(body);
        }
    }

    @SuppressWarnings("unused")
    private static long parseRetryAfter(String v) {
        try {
            return TimeUnit.SECONDS.toMillis(Long.parseLong(v));
        } catch (Exception ignore) {
            return 1000L;
        }
    }
}
