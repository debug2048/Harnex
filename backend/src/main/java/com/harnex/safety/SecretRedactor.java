package com.harnex.safety;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Centralised PII / secret scrubber, shared by
 * {@link com.harnex.audit.LlmAuditLog} and {@code SensitiveDataMiddleware}.
 *
 * <p>
 * The patterns deliberately mirror the most common operational leaks an LLM tool can surface in production deployments:
 *
 * <ul>
 * <li>API tokens emitted by partner SDKs ({@code sk-*}, {@code ghp_*}, {@code xox[baprs]-*}, AWS access keys, HTTP
 * {@code Authorization: Bearer …} headers).</li>
 * <li>Inline credential assignments grepped from config files ({@code password=foo}, {@code api_key: bar}).</li>
 * <li>PII that frequently appears in customer-support traffic and dataset spills: Chinese national ID (18-digit, last
 * char optionally X), Chinese mobile (11 digits starting with 1), credit-card numbers passing the Luhn check (handled
 * separately by the middleware), generic email addresses (kept opt-in because emails frequently SHOULD reach the agent
 * — the middleware exposes a separate flag).</li>
 * </ul>
 *
 * <p>
 * All patterns carry an explicit upper bound on the length quantifier so a long base64 payload echoed by the model
 * cannot trigger catastrophic regex backtracking — same lesson as the existing {@link com.harnex.audit.LlmAuditLog}
 * regex bank.
 *
 * <p>
 * <b>Why centralise instead of letting each subsystem own its own list:</b> form-2's compliance story relies on a
 * SINGLE source of truth — the moment the audit log scrubber and the LLM-payload scrubber diverge, an attacker can
 * craft a payload that one path redacts but the other persists. Reusing this class everywhere closes that gap.
 */
public final class SecretRedactor {

    private SecretRedactor() {}

    /** Token / credential patterns. Each captures group 1 = prefix to keep (or empty for hard-redact). */
    public static final Pattern[] CORE_PATTERNS = new Pattern[] {
        // OpenAI / Anthropic style sk-… and api-key prefixed
        Pattern.compile("(?i)\\bsk-[A-Za-z0-9_-]{16,256}\\b"),
        // GitHub personal access tokens (classic + fine-grained both start ghp_/github_pat_)
        Pattern.compile("(?i)\\bghp_[A-Za-z0-9]{30,256}\\b"),
        Pattern.compile("(?i)\\bgithub_pat_[A-Za-z0-9_]{40,256}\\b"),
        // Slack tokens
        Pattern.compile("(?i)\\bxox[baprs]-[A-Za-z0-9-]{8,256}\\b"),
        // AWS access key id (root or IAM)
        Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
        // Google Cloud API key
        Pattern.compile("\\bAIza[0-9A-Za-z_\\-]{35}\\b"),
        // JSON Web Token (3 base64url segments)
        Pattern.compile("\\beyJ[A-Za-z0-9_\\-]{10,512}\\.[A-Za-z0-9_\\-]{10,512}\\.[A-Za-z0-9_\\-]{10,512}\\b"),
        // Bearer / Authorization headers in any text dump
        Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{16,512}"),
        // password=... / api_key=... assignments grepped from configs.
        // The `proxy` keyword catches the new harnex.http.proxy / providers[].proxy fields when
        // they get serialised into audit JSON / log lines as `proxy=http://u:p@h:port`.
        Pattern.compile("(?i)(password\\s*[:=]\\s*)([^\\s,;\"']{4,256})"),
        Pattern.compile("(?i)(api[_-]?key\\s*[:=]\\s*)([^\\s,;\"']{8,256})"),
        Pattern.compile("(?i)(secret\\s*[:=]\\s*)([^\\s,;\"']{8,256})"),
        // URL userinfo segment — `scheme://user:pass@host[:port][/...]`. Uses a lookahead for
        // the `@` so the host portion survives the redaction (e.g.
        // `http://alice:s3cret@proxy.lan:8080` → `http://[REDACTED]@proxy.lan:8080`).
        // Bounded quantifiers protect against regex backtracking on pathological inputs.
        Pattern.compile("\\b([a-zA-Z][a-zA-Z0-9+.\\-]{0,15}://)[^\\s/@:?#\"']{1,128}:[^\\s/@?#\"']{1,128}(?=@)"),};

    /**
     * PII patterns — opt-in via {@link #redact(String, boolean)} because emails / phone numbers are frequently part of
     * legitimate user requests (e.g. "look up the issue filed by alice@example.com"). Compliance-grade deployments
     * enable; dev / personal deployments leave off.
     */
    public static final Pattern[] PII_PATTERNS = new Pattern[] {
        // Chinese mainland mobile: 1 + 3..9 + 9 digits
        Pattern.compile("\\b1[3-9]\\d{9}\\b"),
        // Chinese 18-digit national ID, last char optionally X (case-insensitive)
        Pattern.compile("\\b[1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[0-9Xx]\\b"),
        // Generic email
        Pattern.compile("\\b[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,253}\\.[A-Za-z]{2,24}\\b"),
        // Credit-card-ish digit groups (13–19 digits, separators tolerant); Luhn left to a
        // future hardening pass — false positives here are preferable to false negatives in
        // a compliance gateway.
        Pattern.compile("\\b(?:\\d[ -]?){13,19}\\b"),};

    /** Redact known credentials only — safe to enable everywhere. */
    public static String redact(String s) {
        return redact(s, false);
    }

    /**
     * Redact credentials AND, when {@code includePii} is true, the PII patterns above. Returns the input unchanged when
     * {@code s} is null/empty so callers can chain.
     */
    public static String redact(String s, boolean includePii) {
        if (s == null || s.isEmpty())
            return s;
        String out = s;
        for (Pattern p : CORE_PATTERNS) {
            out = applyPattern(out, p);
        }
        if (includePii) {
            for (Pattern p : PII_PATTERNS) {
                out = applyPattern(out, p);
            }
        }
        return out;
    }

    private static String applyPattern(String in, Pattern p) {
        return p.matcher(in).replaceAll(m -> {
            if (m.groupCount() >= 1 && m.group(1) != null) {
                return Matcher.quoteReplacement(m.group(1) + "[REDACTED]");
            }
            return "[REDACTED]";
        });
    }
}
