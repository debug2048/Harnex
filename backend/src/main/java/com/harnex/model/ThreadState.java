package com.harnex.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.Data;

/**
 * Per-thread runtime state (sandbox id, uploads, todos, facts snapshot, extra).
 */
@Data
public class ThreadState {
    /** Exact public branch leaf used to build this Agent continuation. */
    private String conversationLeafId;

    private String sandboxId;
    private List<String> uploads = new ArrayList<>();
    private List<Map<String, Object>> todos = new ArrayList<>();
    private Map<String, Object> extras = new LinkedHashMap<>();
    /** Context compaction baseline shared by successive Goal turns on this thread. */
    private CompressionState compression = new CompressionState();
    /**
     * Exact file-slice stamps already inspected during this thread, retained across compression generations. Values
     * contain no file content, only a canonical request key and an on-disk fingerprint. Bounded by FileReadTool;
     * callers can explicitly request a refresh if compacted context no longer contains required source text.
     */
    private Map<String, FileReadCacheEntry> fileReadCache = new LinkedHashMap<>();
    /**
     * Per-thread permission mode. One of:
     * <ul>
     * <li>{@code ask} (default) — every non-whitelisted tool call goes through approval UI.</li>
     * <li>{@code accept-edits} — file_read/file_write/file_edit/memory_* auto-allowed; bash / web_fetch / delegate
     * still ask.</li>
     * <li>{@code yolo} — all tools auto-allowed (dangerous-pattern denylist still applies).</li>
     * </ul>
     * Dangerous-pattern denylist (rm -rf /, mkfs, fork bomb, etc.) is enforced regardless of mode.
     */
    private volatile String permissionMode = "ask";
}
