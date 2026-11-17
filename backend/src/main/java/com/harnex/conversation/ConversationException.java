package com.harnex.conversation;

public class ConversationException extends RuntimeException {
    private final String code;
    public ConversationException(String code, String message) { super(message); this.code = code; }
    public ConversationException(String code, String message, Throwable cause) { super(message, cause); this.code = code; }
    public String code() { return code; }
}
