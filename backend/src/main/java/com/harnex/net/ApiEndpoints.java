package com.harnex.net;

/** Shared URL rules for text and image APIs with a versioned base or an explicit endpoint. */
public final class ApiEndpoints {
    private ApiEndpoints() {}

    public static String ensureVersionPath(String baseUrl) {
        if (baseUrl == null) return null;
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base.matches(".*/(v\\d+)$") ? base : base + "/v1";
    }

    public static String resolveEndpoint(String baseUrl, String endpoint) {
        if (baseUrl == null) return null;
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base.endsWith(endpoint) ? base : ensureVersionPath(base) + endpoint;
    }
}
