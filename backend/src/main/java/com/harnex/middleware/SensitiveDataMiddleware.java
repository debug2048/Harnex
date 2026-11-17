package com.harnex.middleware;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.harnex.config.ProjectRuntime;
import com.harnex.core.AgentCtx;
import com.harnex.model.ChatMessage;
import com.harnex.safety.SecretRedactor;

import lombok.extern.slf4j.Slf4j;

/**
 * Scrub credentials / PII out of tool results before they re-enter the LLM context window.
 *
 * <p>
 * Why this exists: tools like {@code bash} (`cat .env`), {@code file_read} (config files, customer DB exports) and
 * {@code web_fetch} (third-party API responses) are the most common vector for accidentally feeding secrets / PII into
 * the conversation history. Once secrets land in history they get (a) sent to the upstream LLM provider every
 * subsequent turn, (b) persisted into {@code thread.json}, (c) potentially echoed by the model into its own reply. A
 * redaction pass at the {@code afterTools} hook closes all three at once.
 *
 * <p>
 * Operates on tool result messages only (role == {@code "tool"}), the exact rows produced by {@code AgentLoop} when it
 * processes a tool batch. User messages are not redacted — customer-typed PII is signal, not exfiltration. Assistant
 * messages are not redacted either — they're the model's own output and any leak there is a separate problem (the audit
 * log already redacts them via the same {@link SecretRedactor#redact}).
 *
 * <p>
 * Behaviour matrix:
 * <ul>
 * <li>{@code harnex.safety.redact-secrets-in-tool-results = true} (default): scrub credentials only.</li>
 * <li>{@code harnex.safety.redact-pii-in-tool-results = true} (default false): also scrub PII patterns.</li>
 * </ul>
 *
 * <p>
 * Disabling the credential pass is allowed but logged at startup as a WARN so an operator who flips it off (e.g. for a
 * debug session) gets a visible reminder.
 */
@Slf4j
@Component
@ProjectRuntime
public class SensitiveDataMiddleware implements Middleware {

    private final boolean redactSecrets;
    private final boolean redactPii;

    public SensitiveDataMiddleware(@Value("${harnex.safety.redact-secrets-in-tool-results:true}") boolean redactSecrets,
        @Value("${harnex.safety.redact-pii-in-tool-results:false}") boolean redactPii) {
        this.redactSecrets = redactSecrets;
        this.redactPii = redactPii;
        if (!redactSecrets) {
            log.warn("SensitiveDataMiddleware: secret redaction in tool results is DISABLED — "
                + "tool output containing API keys / Bearer tokens will reach the LLM verbatim. "
                + "Set harnex.safety.redact-secrets-in-tool-results=true for compliance deployments.");
        } else if (redactPii) {
            log.info("SensitiveDataMiddleware: PII redaction enabled (compliance gateway mode)");
        }
    }

    @Override
    public int order() {
        return MiddlewareOrder.SENSITIVE_DATA_REDACTION;
    }

    @Override
    public void afterTools(AgentCtx ctx) {
        if (!redactSecrets && !redactPii) {
            return;
        }
        List<ChatMessage> msgs = ctx.getThread().getMessages();
        if (msgs.isEmpty()) {
            return; // Find the most recent contiguous run of tool-role messages and scrub all of them.
        }
        //
        // The previous tail-walk broke on the FIRST non-tool message, which silently skipped
        // redaction whenever an earlier-ordered afterTools middleware had appended something
        // after the tool batch. Built-in reminders are now request-scoped and no longer do that, but custom
        // middleware can still append a message before this middleware (order=300) runs. We deliberately keep
        // SENSITIVE_DATA_REDACTION late in the chain so user-defined ConfigurableHooks
        // (order=200) see raw tool output for vetoing decisions. So instead of changing the
        // order, the scan now:
        // 1. skips any trailing non-tool messages (for example, custom middleware output), then
        // 2. scrubs every contiguous tool message that precedes the first non-tool, then
        // 3. stops — earlier batches were already scrubbed in their own iteration.
        // AgentLoop appends tool results contiguously after the assistant call that
        // requested them, so step (2) covers exactly one batch per invocation.
        int i = msgs.size() - 1;
        while (i >= 0 && !"tool".equals(msgs.get(i).getRole())) {
            i--;
        }
        for (; i >= 0; i--) {
            ChatMessage m = msgs.get(i);
            if (!"tool".equals(m.getRole())) {
                break;
            }
            String original = m.getContent();
            if (original == null || original.isEmpty()) {
                continue;
            }
            String scrubbed = SecretRedactor.redact(original, redactPii);
            if (!scrubbed.equals(original)) {
                m.setContent(scrubbed);
                log.debug("SensitiveDataMiddleware: redacted {} chars from tool result (call={})",
                    original.length() - scrubbed.length(), m.getToolCallId());
            }
        }
    }

    /**
     * Apply the same redaction policy used by {@link #afterTools} to a single tool-result payload, without needing an
     * {@link AgentCtx} or a thread to attach to. Shared by the agent loop, lesson recording and MCP server responses.
     * Returns the input unchanged when both flags are off or the input is null/empty so callers can substitute the
     * result unconditionally.
     */
    public String redactToolContent(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        if (!redactSecrets && !redactPii) {
            return content;
        }
        return SecretRedactor.redact(content, redactPii);
    }
}
