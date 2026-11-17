package com.harnex.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.harnex.conversation.ContextEvidence;
import com.harnex.conversation.HistoryReference;
import com.harnex.conversation.RequestContextOverlay;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * OpenAI-style chat message. Roles: system / user / assistant / tool.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class ChatMessage {
    private String role;
    private String content;
    /** For role=assistant: tool calls requested by the model. */
    private List<ToolCall> toolCalls;
    /** For role=tool: id of the originating tool_call. */
    private String toolCallId;
    /** For role=tool: authoritative execution outcome used by deterministic compaction and diagnostics. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean toolError;
    /** Optional reasoning text (extracted from model response). */
    private String reasoning;
    /**
     * When true, this assistant message is a standalone reasoning/thinking block, not a final answer. Used to render
     * each thinking phase as a separate timeline node.
     */
    private boolean reasoningBlock;
    /**
     * Provider identity that produced {@link #reasoning} / {@link #reasoningToken} (e.g. {@code "anthropic"},
     * {@code "openai-responses"}, or {@code "openai-compatible:kimi"}). Used to gate cross-turn reasoning replay: an
     * adapter only re-injects reasoning it emitted, so failover never sends one provider's reasoning state to another.
     * Null for plain text/tool turns and for threads created before reasoning provenance existed.
     */
    private String reasoningProvider;
    /**
     * Opaque, provider-specific reasoning-continuation token that must be replayed verbatim on the next turn to keep a
     * reasoning model operating at full quality (and, with tool use, to satisfy APIs that require the reasoning block
     * before the tool call). Anthropic stores the thinking-block {@code signature} here; OpenAI Responses stores the
     * encrypted reasoning item(s) JSON ({@code reasoning.encrypted_content}). Never rendered — pure round-trip
     * metadata.
     */
    private String reasoningToken;
    /**
     * Provider family that produced {@link #responseOutputItems}. Kept separate from {@link #reasoningProvider}
     * because the latter may describe an Anthropic thinking signature while this field represents a complete
     * provider-native response turn. Adapters must only replay items tagged with their own endpoint identity.
     */
    private String responseOutputProvider;
    /**
     * Opaque JSON array of the provider's complete native assistant output, persisted verbatim for stateless manual
     * history replay. OpenAI Responses uses output items (including ids, phase and caller linkage); Anthropic uses the
     * ordered content-block array (including signed/redacted thinking and tool-use blocks); Gemini Interactions uses
     * ordered steps (including thought and built-in-tool signatures). Null for providers that do not expose typed
     * output.
     */
    private String responseOutputItems;
    /**
     * Files attached to this user message (images / docs). Persisted so that subsequent turns can re-emit images to the
     * LLM without the user re-uploading. Empty/null on assistant/tool/system messages.
     */
    private List<MessageAttachment> attachments;

    /**
     * Durable control-plane identities applied by this user message. A Steering checkpoint writes these ids before
     * marking the mailbox records APPLIED, so restart recovery can detect a committed message and converge the
     * mailbox without injecting the same user input twice. Never forwarded by provider adapters.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> runInputIds;

    /**
     * Free-form short tag identifying where this turn entered the system: {@code "webui"} / {@code "tui"} / IM channel
     * id ({@code "feishu"} / {@code "dingtalk"} / {@code "wecom"} / {@code "webhook"}) / scheduler id
     * ({@code "cron:<jobName>"}) / {@code "alertmanager"}. The frontend renders this as a small badge next to the
     * message author so an operator scanning a long thread can tell at a glance which turns came from automation vs. a
     * human typing in the UI. {@code null} on system / tool messages.
     *
     * <p>
     * Persisted in {@code thread.json}. Pure presentation hint — no part of the agent loop branches on it
     * (deliberately: keeps the data layer free of channel-specific behaviour).
     */
    private String channel;
    /**
     * Optional sub-source within {@link #channel}. For IM gateways this is the upstream user id (e.g. Feishu open_id,
     * DingTalk staffId); for {@code cron:*} it is the job name; for {@code alertmanager} it is the alert name. Used by
     * the WebUI badge tooltip; never sent to the LLM.
     */
    private String source;

    /** Original user input before directive expansion (e.g. "/skill xxx input"). Used for display/title. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String originalInput;

    /** Archive provenance survives projection/compaction without adding a source directory to the model prompt. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @Schema(hidden = true)
    private List<HistoryReference> contextSources;
    /** Exact archive fragments known to occur in the source body; lossy summaries do not inherit this proof. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @Schema(hidden = true)
    @ToString.Exclude
    private List<ContextEvidence.Fragment> contextEvidence;
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    @Schema(hidden = true)
    private boolean contextSourcesPartial;
    /** Small, rebuildable recall projection attached to this human turn; rendered only at the provider boundary. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @Schema(hidden = true)
    private String recalledHistory;

    /** Exact derived context already sent with this input/result; not part of the authoritative archive content. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(hidden = true)
    @ToString.Exclude
    private RequestContextOverlay requestContextOverlay;

    /** Pure previews carry a commit handle on copies; no live history is changed until the request is admitted. */
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private transient RequestContextOverlay.Prepared preparedContext;

    public static ChatMessage user(String text) {
        return ChatMessage.builder().role("user").content(text).build();
    }

    public static ChatMessage system(String text) {
        return ChatMessage.builder().role("system").content(text).build();
    }

    public static ChatMessage assistant(String text) {
        return ChatMessage.builder().role("assistant").content(text).build();
    }

    public static ChatMessage tool(String callId, String content) {
        return ChatMessage.builder().role("tool").toolCallId(callId).content(content).build();
    }

    public static ChatMessage tool(String callId, String content, boolean error) {
        return ChatMessage.builder().role("tool").toolCallId(callId).toolError(error).content(content).build();
    }
}
