package com.harnex.tool.budget;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.ModelType;

import lombok.extern.slf4j.Slf4j;

/**
 * Real BPE token counter (jtokkit) with graceful fallback.
 *
 * <p>
 * Used by {@link BudgetEnforcer} so tool-result truncation matches the actual provider billing instead of a raw char
 * count — a 8000-char Chinese / code response can be 4000–6000 real tokens, blowing past the cap the operator
 * configured to control cost.
 *
 * <p>
 * Strategy:
 * <ul>
 * <li>Known OpenAI / OAI-compat model — use jtokkit's per-model registry (gpt-4o, gpt-4.1, o3, o4, gpt-3.5, ...).</li>
 * <li>Prefix-matched OpenAI variants ({@code gpt-5.6-*}, {@code gpt-4o-mini-...}, {@code gpt-4.1-...}) — fall through to
 * {@code o200k_base}.</li>
 * <li>Legacy OpenAI ({@code gpt-3.5-*}, {@code gpt-4-*}) — {@code cl100k_base}.</li>
 * <li>Anthropic / local / unknown — a conservative script-aware approximation. Anthropic's tokeniser is not
 * published as a portable Java library; ASCII is estimated at four characters/token while non-ASCII UTF-16 units
 * count as one token each so CJK and emoji are not systematically under-budgeted.</li>
 * </ul>
 *
 * <p>
 * Thread-safe; the underlying jtokkit {@link Encoding} instances are immutable.
 */
@Slf4j
@Component
public class TokenCounter {

    /** Conservative average for ASCII-heavy English/code. */
    private static final int FALLBACK_CHARS_PER_TOKEN = 4;

    private final EncodingRegistry registry;
    /** Cache encoder lookups so per-call we do at most one ConcurrentHashMap get. */
    private final ConcurrentHashMap<String, Optional<Encoding>> cache = new ConcurrentHashMap<>();

    public TokenCounter() {
        EncodingRegistry r;
        try {
            r = Encodings.newDefaultEncodingRegistry();
        } catch (RuntimeException e) {
            // jtokkit failed to load encoding tables (corrupt jar, classloader sandbox).
            // Falling back to the script-aware approximation is the right move — token budgeting becomes approximate
            // but the agent loop keeps working.
            log.warn("jtokkit registry init failed; using script-aware approximation "
                + "(ASCII chars/token={}) for all models: {}", FALLBACK_CHARS_PER_TOKEN, e.getMessage());
            r = null;
        }
        this.registry = r;
    }

    /**
     * Count tokens in {@code text} as the given {@code model} would bill them. Returns {@code 0} for null/empty input
     * regardless of model. Never throws.
     */
    public int count(String text, String model) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        Encoding enc = encoderFor(model).orElse(null);
        if (enc == null) {
            // Round UP so a budget check on the boundary value still trips the truncation path.
            return approximateTokens(text);
        }
        try {
            return enc.countTokens(text);
        } catch (RuntimeException e) {
            // jtokkit very rarely throws (only on encoder bugs); never let a counter failure
            // abort the agent loop. Fall back to the script-aware estimate for this single call.
            log.debug("jtokkit countTokens failed for model={} ({} chars): {}", model, text.length(), e.getMessage());
            return approximateTokens(text);
        }
    }

    /**
     * True iff this counter is using a real BPE encoder for {@code model}. Lets callers (e.g. SelfInspectTool) report
     * whether the token figures shown to the user are exact or approximate.
     */
    public boolean isExact(String model) {
        return encoderFor(model).isPresent();
    }

    /**
     * Approximate inverse of {@link #count}: estimate how many characters of {@code text} fit within
     * {@code targetTokens}. Used by {@link BudgetEnforcer} to pick a first-cut preview length before re-verifying with
     * {@link #count}. Always returns at least 1 when {@code targetTokens > 0} and {@code text} is non-empty so the
     * truncation loop makes progress.
     */
    public int charsForTokens(String text, String model, int targetTokens) {
        if (text == null || text.isEmpty() || targetTokens <= 0) {
            return 0;
        }
        int actualTokens = count(text, model);
        if (actualTokens <= targetTokens) {
            return text.length();
        }
        // 95% safety margin: BPE compression varies inside a single string (a token-dense
        // prefix followed by sparse tail can mis-estimate by 5–10%), so undercut slightly and
        // let the caller verify + extend.
        long est = (long)text.length() * targetTokens * 95L / (actualTokens * 100L);
        return Math.max(1, Math.min(text.length(), (int)est));
    }

    private Optional<Encoding> encoderFor(String model) {
        if (registry == null) {
            return Optional.empty();
        }
        String key = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        return cache.computeIfAbsent(key, this::resolveEncoder);
    }

    private Optional<Encoding> resolveEncoder(String key) {
        // 1. Exact model lookup via jtokkit's bundled enum.
        for (ModelType mt : ModelType.values()) {
            if (mt.getName().equalsIgnoreCase(key)) {
                try {
                    return Optional.ofNullable(registry.getEncodingForModel(mt));
                } catch (RuntimeException e) {
                    break;
                }
            }
        }
        // 2. Prefix heuristics for model families jtokkit's enum lags behind on (gpt-4.1, o3, o4, ...).
        // All of these share o200k_base per OpenAI's published tokeniser table.
        if (key.equals("gpt-5") || key.startsWith("gpt-5-") || key.startsWith("gpt-5.")
            || key.startsWith("gpt-4o") || key.startsWith("gpt-4.1") || key.startsWith("o1") || key.startsWith("o3")
            || key.startsWith("o4") || key.startsWith("chatgpt-4o")) {
            return tryEncoding(EncodingType.O200K_BASE);
        }
        // 3. Legacy OpenAI families on cl100k_base.
        if (key.startsWith("gpt-4") || key.startsWith("gpt-3.5") || key.startsWith("text-embedding-3")) {
            return tryEncoding(EncodingType.CL100K_BASE);
        }
        // 4. Anything else (anthropic claude-*, gemini-*, local llama/qwen, unknown OAI-compat) — no
        // portable Java tokeniser; signal "use the conservative script-aware fallback".
        return Optional.empty();
    }

    private Optional<Encoding> tryEncoding(EncodingType t) {
        try {
            return Optional.ofNullable(registry.getEncoding(t));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static int approximateTokens(String text) {
        long asciiUnits = 0;
        long nonAsciiUnits = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) <= 0x7f) {
                asciiUnits++;
            } else {
                // Counting UTF-16 units deliberately makes surrogate-pair emoji conservative.
                nonAsciiUnits++;
            }
        }
        long estimate = (asciiUnits + FALLBACK_CHARS_PER_TOKEN - 1) / FALLBACK_CHARS_PER_TOKEN + nonAsciiUnits;
        return (int)Math.min(Integer.MAX_VALUE, estimate);
    }
}
