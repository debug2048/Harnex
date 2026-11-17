package com.harnex.provider;

/** Complete non-streaming model result, including upstream-reported usage when available. */
public record OneShotResult(String content, String provider, String model, ProviderTokenUsage usage) {

    public OneShotResult {
        content = content == null ? "" : content;
    }
}
