package com.harnex.provider;

/** Shared planning policy for summary retention and generation; all sizes are tokens unless named otherwise. */
public final class SummaryBudget {
    public static final String POLICY = "adaptive-summary-v1";
    public static final int ENVELOPE_TOKENS = 96;
    public static final int MIN_BODY_TOKENS = 64;

    private SummaryBudget() {}

    /** Capacity of the model generating the summary, independently of the model that will consume it. */
    public record ModelCapacity(int contextWindow, int safetyReserve, Integer maxOutputTokens, boolean reasoning) {
        public int usableTokens() { return Math.max(0, contextWindow - Math.max(256, safetyReserve)); }
    }

    /** At most one quarter of space left after mandatory input; the rest remains available for original history. */
    public static int retention(long availableTokens, int configuredOutputLimit) {
        long block = Math.min(Math.max(0, availableTokens) / 4, Math.max(0L, configuredOutputLimit) + ENVELOPE_TOKENS);
        return block >= ENVELOPE_TOKENS + MIN_BODY_TOKENS ? (int)block : 0;
    }

    public static int bodyTokens(long retainedBlockTokens) {
        return (int)Math.max(0, Math.min(Integer.MAX_VALUE, retainedBlockTokens - ENVELOPE_TOKENS));
    }

    /** Reserves are planning shares, not independent provider controls or guarantees about reasoning length. */
    public record Generation(int outputTokens, int bodyTokens, int factTokens, int reasoningTokens) {
        public boolean usable() { return bodyTokens >= MIN_BODY_TOKENS; }

        public String instructions(int maxCharacters) {
            return "\nSummary budget: keep the six Markdown sections within " + bodyTokens + " tokens and "
                + maxCharacters + " characters. This is an upper limit, not a target length."
                + (factTokens > 0 ? " The separate fact-candidate trailer has approximately " + factTokens
                    + " tokens available; retain complete source quotations and return a complete trailer." : "")
                + " Prioritize active constraints and open work; condense completed background. Do not repeat original messages that remain verbatim.\n";
        }
    }

    public static Generation generation(ModelCapacity capacity, long inputTokens, int configuredOutputLimit,
        int retainedBodyTokens, boolean facts) {
        int shares = 1 + (facts ? 1 : 0) + (capacity.reasoning() ? 2 : 0);
        long ceiling = outputCeiling(capacity, configuredOutputLimit);
        ceiling = Math.min(ceiling, Math.max(0, (long)capacity.usableTokens() - Math.max(0, inputTokens)));
        int output = (int)Math.min(ceiling, Math.max(0L, retainedBodyTokens) * shares);
        int body = Math.min(Math.max(0, retainedBodyTokens), output / shares);
        int fact = facts ? body : 0;
        int reasoning = capacity.reasoning() ? output - body - fact : 0;
        return new Generation(output, body, fact, reasoning);
    }

    /** Initial source-selection budget; exact prepared input is counted again before every attempt. */
    public static int inputCapacity(ModelCapacity capacity, int configuredOutputLimit, int retainedBodyTokens, boolean facts) {
        int shares = 1 + (facts ? 1 : 0) + (capacity.reasoning() ? 2 : 0);
        long wanted = Math.max(0L, retainedBodyTokens) * shares;
        long reserve = Math.min(outputCeiling(capacity, configuredOutputLimit), Math.min(wanted, capacity.usableTokens() / 4));
        return (int)Math.max(0, capacity.usableTokens() - reserve);
    }

    private static long outputCeiling(ModelCapacity capacity, int configuredOutputLimit) {
        return Math.min(Math.max(0, configuredOutputLimit), capacity.maxOutputTokens() == null
            ? Integer.MAX_VALUE : Math.max(0, capacity.maxOutputTokens()));
    }
}
