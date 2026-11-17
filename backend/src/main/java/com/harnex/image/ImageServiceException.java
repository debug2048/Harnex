package com.harnex.image;

import com.harnex.net.HttpExchangeState.Acceptance;

public final class ImageServiceException extends RuntimeException {
    private final String code;
    private final Acceptance acceptance;
    private final long retryAfterMillis;
    public ImageServiceException(String code, String message) { this(code, message, Acceptance.NOT_SENT, 0); }
    public ImageServiceException(String code, String message, Acceptance acceptance, long retryAfterMillis) {
        super(message); this.code = code; this.acceptance = acceptance; this.retryAfterMillis = Math.max(0, retryAfterMillis);
    }
    public String code() { return code; }
    public Acceptance acceptance() { return acceptance; }
    public long retryAfterMillis() { return retryAfterMillis; }
}
