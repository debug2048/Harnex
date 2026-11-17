package com.harnex.conversation;

/** A locator into authoritative history. Runtime metadata only; never a replacement for the referenced source. */
public record HistoryReference(String conversationId, String messageId, String executionRecordId, long revision) {
    public String key() { return conversationId + ":" + (messageId == null ? "execution:" + executionRecordId : messageId); }
}
