package com.harnex.net;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses the POSIX {@code NO_PROXY} environment variable into bypass entries compatible with
 * {@link ProxyResolution#matchesBypass(String, List)}.
 *
 * <p>
 * <strong>Dialect:</strong> curl's interpretation, the most widely supported subset. Supports:
 * <ul>
 * <li>Comma-separated host entries; whitespace tolerated</li>
 * <li>Bare hostnames ({@code example.com})</li>
 * <li>Leading-dot suffixes ({@code .example.com}) &mdash; translated to glob {@code *.example.com} plus the bare apex
 * ({@code example.com}), matching curl/Node/Go behaviour</li>
 * <li>Glob suffixes that already start with {@code *} ({@code *.internal}) &mdash; passed through</li>
 * <li>{@code host:port} &mdash; port is silently dropped (our bypass match is host-only; documenting the limitation is
 * simpler than adding a per-port match dimension that no other tool consistently honours)</li>
 * <li>The single special value {@code *} &mdash; "bypass everything", emitted as glob {@code *}</li>
 * </ul>
 *
 * <p>
 * <strong>Intentionally NOT supported:</strong> CIDR notation ({@code 10.0.0.0/8}). Different tools disagree on whether
 * to accept it (curl: no, Go: yes, Python: depends on library). Users who need CIDR semantics should rely on the
 * built-in RFC1918 / loopback bypass already enforced by {@link ProxyResolution#isBuiltInBypass(String)}.
 */
public final class NoProxyEnvParser {

    private NoProxyEnvParser() {}

    public static List<String> parse(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String tokenRaw : raw.split(",")) {
            String token = tokenRaw.trim().toLowerCase(Locale.ROOT);
            if (token.isEmpty()) {
                continue;
            }
            // ── Normalise IPv6 + strip optional port suffix ────────────────────────────
            // Three input shapes have to be handled distinctly:
            // 1) bracketed IPv6 with port → "[2001:db8::1]:443" strip "]:443" → "[2001:db8::1]"
            // 2) bracketed IPv6 no port → "[2001:db8::1]" keep as-is
            // 3) bare IPv6 (≥2 colons, no brackets) → "2001:db8::1" do NOT strip — the last
            // ":1" looks like a port but isn't. Wrap in brackets so it matches what
            // URI.getHost() returns for the same address (ProxyResolution feeds that into
            // matchesBypass).
            // 4) host:port (single colon, digit tail) → "host:8443" strip port → "host"
            if (token.startsWith("[")) {
                int rb = token.indexOf(']');
                if (rb > 0) {
                    String host = token.substring(0, rb + 1); // include closing bracket
                    // anything after "]" is either empty or ":<port>" — drop it either way
                    token = host;
                }
            } else if (countColons(token) >= 2) {
                // bare IPv6 — normalise to bracketed form so it equals URI.getHost() output
                token = "[" + token + "]";
            } else {
                int colon = token.lastIndexOf(':');
                if (colon > 0 && colon < token.length() - 1) {
                    String tail = token.substring(colon + 1);
                    if (isAllDigits(tail)) {
                        token = token.substring(0, colon);
                    }
                }
            }
            if (token.isEmpty()) {
                continue;
            }
            if ("*".equals(token)) {
                out.add("*");
                continue;
            }
            if (token.startsWith(".")) {
                // ".example.com" → both *.example.com and example.com (curl semantics).
                String apex = token.substring(1);
                if (!apex.isEmpty()) {
                    out.add("*" + token); // *.example.com
                    out.add(apex); // example.com
                }
                continue;
            }
            out.add(token);
        }
        return out;
    }

    private static int countColons(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == ':') {
                n++;
            }
        }
        return n;
    }

    private static boolean isAllDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
