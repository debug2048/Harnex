package com.harnex.tool.builtin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.harnex.config.ProjectRuntime;
import com.harnex.safety.FileAccessTracker;
import com.harnex.workspace.WorkspacePathResolver;
import com.harnex.model.CompressionState;
import com.harnex.model.FileReadCacheEntry;
import com.harnex.attachment.DocumentTextExtractor;
import com.harnex.tool.Tool;
import com.harnex.tool.ToolCapability;
import com.harnex.tool.ToolContext;
import com.harnex.tool.ToolDefinition;
import com.harnex.tool.ToolResult;
import com.harnex.util.Utf8;

@Component
@ProjectRuntime
public class FileReadTool implements Tool {

    /** Hard cap on returned bytes when neither {@code maxBytes} nor {@code limit} is given. */
    private static final long DEFAULT_MAX_BYTES = 2L * 1024 * 1024;
    /** Absolute upper bound; any explicit {@code maxBytes} is clamped to this. */
    private static final long ABSOLUTE_MAX_BYTES = 10_000_000L;
    /** Probe window for binary-detection (NUL byte sniffing). */
    private static final int BINARY_PROBE = 4096;
    /**
     * Per-line cap in line-mode. Source lines above 2,000 characters are usually minified/log artefacts; without this
     * cap a single multi-MB line would bypass the byte limit.
     */
    private static final int MAX_LINE_CHARS = 2_000;
    private static final int MAX_DEFAULT_LINES = 2_000;
    private static final int MAX_READ_CACHE_ENTRIES = 256;
    private static final long HASH_FINGERPRINT_MAX_BYTES = 4L * 1024 * 1024;

    private final WorkspacePathResolver vp;
    private final FileAccessTracker tracker;
    private final DocumentTextExtractor extractor;

    @Autowired
    public FileReadTool(WorkspacePathResolver vp, FileAccessTracker tracker,
        DocumentTextExtractor extractor) {
        this.vp = vp;
        this.tracker = tracker;
        this.extractor = extractor;
    }

    /** Test constructor with a tracker but no document extractor. */
    public FileReadTool(WorkspacePathResolver vp, FileAccessTracker tracker) {
        this(vp, tracker, null);
    }

    /** Test-only constructor — production code uses Spring DI with a real tracker + extractor. */
    public FileReadTool(WorkspacePathResolver vp) {
        this(vp, null, null);
    }

    /**
     * Record a successful read in the access tracker (no-op if no tracker / no thread). Uses {@code recordHashed} so
     * the stamp includes a SHA-256 of small files; this closes the same-mtime+same-size false-OK window on coarse-mtime
     * filesystems for the common read \u2192 edit cycle. {@code recordHashed} self-skips the hash for files >4 MiB so
     * the cost stays bounded for large reads.
     */
    private void track(Path p, ToolContext ctx) {
        if (tracker != null && ctx != null && ctx.getThread() != null) {
            tracker.recordHashed(ctx.getThread().getId(), p);
        }
    }

    @Override
    public String getName() {
        return "file_read";
    }

    @Override
    public String getDescription() {
        return "Read a file from the workspace. Text files are read directly; PDF, Office/OpenDocument, RTF/email, "
            + "and OCR-capable images are converted to extracted text when possible. Supports byte cap (maxBytes) "
            + "and line slicing (offset/limit). Refuses unsupported binary files. Image OCR extracts text only; "
            + "it does not inspect visual appearance. For visual inspection use image_inspect if offered, with image asset IDs.";
    }

    @Override
    public Set<ToolCapability> capabilities() {
        return EnumSet.of(ToolCapability.READS_FILESYSTEM,
            ToolCapability.PLAN_MODE_SAFE, ToolCapability.CONCURRENCY_SAFE);
    }

    /** A6: cheap, side-effect-free — allow up to 8 concurrent reads in a single batch. */
    @Override
    public int maxParallel() {
        return 8;
    }

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path",
            Map.of("type", "string", "description", "Absolute path, or a path relative to the project root"));
        props.put("maxBytes", Map.of("type", "integer", "description",
            "Maximum bytes to return (default 2 MiB; absolute max 10,000,000). Use offset/limit to page large files."));
        props.put("offset",
            Map.of("type", "integer", "description", "1-based starting line (inclusive). Triggers line-mode reading."));
        props.put("limit", Map.of("type", "integer", "description",
            "Number of lines to read starting at offset (default 2000, max 2000)."));
        props.put("refresh", Map.of("type", "boolean", "description",
            "Return unchanged content again even when this exact slice was already read. Use only when exact source "
                + "text is no longer available after context compaction."));
        return ToolDefinition.builder().name(getName()).description(getDescription())
            .parameters(Map.of("type", "object", "properties", props, "required", new String[] {"path"})).build();
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        String path = String.valueOf(input.getOrDefault("path", ""));
        Path p = vp.toReal(path, ctx.getThread().getId());
        try {
            if (!Files.exists(p)) {
                return ToolResult.builder().error(true).content("not found: " + p).build();
            }
            if (Files.isDirectory(p)) {
                return ToolResult.builder().error(true).content("path is a directory: " + p).build();
            }
            // Refuse non-regular files (FIFO / char / block / socket). Without this guard, a
            // prior `bash mkfifo x` would let `file_read x` block in Files.readString() until
            // a writer appears; per-call timeouts can cancel the future but the underlying
            // thread stays parked, eventually starving the tool worker pool. NOFOLLOW so a
            // symlink to a FIFO is also caught (the resolver already validates the link
            // target stays in-sandbox, but a sandbox-internal FIFO is still a hang vector).
            if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                return ToolResult.builder().error(true)
                    .content("refusing to read non-regular file (FIFO/device/socket): " + p).build();
            }
            Path original = p;
            Path sidecar = p.resolveSibling(p.getFileName() + ".extracted.txt");
            boolean usingSidecar = false;
            if (Files.isRegularFile(sidecar)) {
                // Defence-in-depth: the primary path was containment-checked by WorkspacePathResolver,
                // but the sidecar is a separate inode that could be (or contain) a symlink planted
                // out of band. Re-run the resolver-level check on the sidecar so a planted symlink
                // can never redirect file_read to /etc/passwd or similar.
                try {
                    Path realSidecar = sidecar.toRealPath();
                    Path realOriginalParent =
                        original.getParent() == null ? original.toRealPath() : original.getParent().toRealPath();
                    if (!realSidecar.startsWith(realOriginalParent)) {
                        return ToolResult.builder().error(true)
                            .content("sidecar resolves outside the file's directory — refusing: " + sidecar).build();
                    }
                    // Also re-run zone classification on the sidecar's real target: even within the original's
                    // directory it must not resolve into a RED (sensitive) zone before we read it.
                    if (vp.classify(realSidecar, ctx.getThread().getId()) == WorkspacePathResolver.Zone.RED) {
                        return ToolResult.builder().error(true)
                            .content("sidecar resolves into a sensitive (red zone) path — refusing: " + sidecar)
                            .build();
                    }
                } catch (IOException ioe) {
                    return ToolResult.builder().error(true)
                        .content("sidecar containment check failed: " + ioe.getMessage()).build();
                }
                p = sidecar;
                usingSidecar = true;
            }
            long size = Files.size(p);
            String readKey = readCacheKey(original, input);
            String wholeReadKey = wholeReadCacheKey(original);
            String fingerprint = fingerprint(p, size);
            ToolResult duplicate = unchangedRead(readKey, wholeReadKey, fingerprint, original, input, ctx);
            if (duplicate != null) {
                track(original, ctx);
                return duplicate;
            }
            // --- on-demand extraction for non-text document / image types (PDF / Office / RTF / image+OCR) ---
            // Decide by MIME, NOT by the NUL-byte binary sniff: many PDFs/RTFs/Office files carry no NUL in the
            // probe window, so a sniff-only gate would feed their raw bytes to a UTF-8 read (mojibake) and never
            // generate the .extracted.txt sidecar. Plain text/code (text/*) is intentionally excluded so it keeps
            // going through the normal text read path below.
            if (!usingSidecar && extractor != null) {
                String mime = extractor.detectMime(original);
                boolean extractableDoc = (extractor.isParseable(mime) && shouldExtractAsDocument(mime, original))
                    || (extractor.isImage(mime) && extractor.ocrUsable());
                if (extractableDoc) {
                    ToolResult r = tryExtract(original, sidecar, mime, input, ctx);
                    // It's a known binary document type: return the extracted text, or refuse — never fall
                    // through to a UTF-8 read that would mojibake the binary.
                    return r != null ? rememberSuccessful(r, readKey, wholeReadKey, fingerprint, false, ctx)
                        : binaryRefusal(size, original, sidecar);
                }
            }
            // --- binary sniff (catches unknown binaries; also a last chance for extractable binaries) ----------
            if (looksBinary(p, size)) {
                if (!usingSidecar && extractor != null) {
                    ToolResult r = tryExtract(original, sidecar, null, input, ctx);
                    if (r != null) {
                        return rememberSuccessful(r, readKey, wholeReadKey, fingerprint, false, ctx);
                    }
                }
                return binaryRefusal(size, original, sidecar);
            }
            // --- line-mode (offset/limit) ------------------------------------
            Integer offset = asInt(input.get("offset"));
            Integer limit = asInt(input.get("limit"));
            if (offset != null || limit != null) {
                int off = offset == null ? 1 : Math.max(1, offset);
                int lim = limit == null ? MAX_DEFAULT_LINES : Math.max(1, Math.min(limit, MAX_DEFAULT_LINES));
                StringBuilder sb = new StringBuilder();
                int read = 0;
                int idx = 0;
                int truncatedLines = 0;
                boolean bytesCapHit = false;
                long outBytes = 0;
                try (BufferedReader r =
                    new BufferedReader(new InputStreamReader(Files.newInputStream(p), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        idx++;
                        if (idx < off)
                            continue;
                        if (read >= lim)
                            break;
                        // Per-line truncation: a minified/log file may have a multi-MB single
                        // line that bypasses our default byte cap entirely (line mode previously
                        // only limited the line *count*). 2,000 chars still covers ordinary source
                        // while keeping a default 2,000-line page bounded.
                        if (line.length() > MAX_LINE_CHARS) {
                            line = line.substring(0, MAX_LINE_CHARS) + " \u2026(line truncated; "
                                + (line.length() - MAX_LINE_CHARS) + " more chars)";
                            truncatedLines++;
                        }
                        // Cumulative byte cap: backstop so total output cannot exceed the same
                        // ABSOLUTE_MAX_BYTES that byte-cap mode enforces. Counted in UTF-8 bytes
                        // (not UTF-16 chars — non-ASCII text would otherwise overshoot the cap
                        // up to 3x once serialised). Stop appending and report — the caller can
                        // re-slice with a smaller limit.
                        long lineBytes = utf8Length(line) + 1; // +1 for the trailing '\n'
                        if (outBytes + lineBytes > ABSOLUTE_MAX_BYTES) {
                            bytesCapHit = true;
                            break;
                        }
                        sb.append(line).append('\n');
                        outBytes += lineBytes;
                        read++;
                    }
                }
                if (truncatedLines > 0) {
                    sb.append("[... ").append(truncatedLines).append(" line(s) truncated at ").append(MAX_LINE_CHARS)
                        .append(" chars each ...]\n");
                }
                if (bytesCapHit) {
                    sb.append("[... output stopped at ").append(ABSOLUTE_MAX_BYTES)
                        .append(" bytes; re-call with a smaller limit or offset ...]\n");
                }
                track(original, ctx);
                return rememberSuccessful(ToolResult.builder().content(prefix(usingSidecar, original, p) + sb).build(),
                    readKey, wholeReadKey, fingerprint, false, ctx);
            }
            // --- byte-cap mode -----------------------------------------------
            long maxBytes;
            Object mb = input.get("maxBytes");
            if (mb instanceof Number n) {
                maxBytes = Math.max(1L, Math.min(n.longValue(), ABSOLUTE_MAX_BYTES));
            } else {
                maxBytes = DEFAULT_MAX_BYTES;
            }
            if (size <= maxBytes) {
                String s = Files.readString(p, StandardCharsets.UTF_8);
                track(original, ctx);
                return rememberSuccessful(ToolResult.builder().content(prefix(usingSidecar, original, p) + s).build(),
                    readKey, wholeReadKey, fingerprint, true, ctx);
            }
            byte[] buf = new byte[(int)maxBytes];
            try (var in = Files.newInputStream(p)) {
                int got = 0;
                while (got < buf.length) {
                    int n = in.read(buf, got, buf.length - got);
                    if (n <= 0)
                        break;
                    got += n;
                }
                // Guard against cutting in the middle of a multi-byte UTF-8 sequence —
                // otherwise `new String(buf, 0, got, UTF_8)` would silently emit U+FFFD
                // and feed a slightly-wrong string to the model.
                int safeGot = Utf8.safeEnd(buf, got);
                String s = new String(buf, 0, safeGot, StandardCharsets.UTF_8);
                long truncated = size - safeGot;
                // Truncated reads STILL count for read-before-edit: the model has SEEN the file
                // (the head of it). The (mtime,size) stamp matches the on-disk state, so any
                // intervening external write will still trip the staleness check on edit.
                track(original, ctx);
                return rememberSuccessful(ToolResult.builder().content(prefix(usingSidecar, original, p) + s
                    + "\n[... truncated " + truncated + " more bytes; use offset/limit"
                    + " or larger maxBytes to see the rest ...]").build(), readKey, wholeReadKey, fingerprint, false,
                    ctx);
            }
        } catch (Exception e) {
            return ToolResult.failure(e);
        }
    }

    private static ToolResult unchangedRead(String key, String wholeKey, String fingerprint, Path original,
        Map<String, Object> input, ToolContext ctx) {
        if (Boolean.TRUE.equals(input.get("refresh"))) return null;
        if (ctx == null || ctx.getThread() == null || ctx.getThread().getState() == null) return null;
        var state = ctx.getThread().getState();
        synchronized (state) {
            if (state.getFileReadCache() == null) state.setFileReadCache(new LinkedHashMap<>());
            CompressionState compression = state.getCompression();
            long generation = compression == null ? 0L : compression.getContextGeneration();
            FileReadCacheEntry prior = state.getFileReadCache().get(key);
            boolean exact = prior != null && fingerprint.equals(prior.getFingerprint());
            if (!exact) {
                prior = state.getFileReadCache().get(wholeKey);
                if (prior == null || !fingerprint.equals(prior.getFingerprint())) return null;
            }
            prior.setContextGeneration(generation);
            return ToolResult.ok("[unchanged file_read deduplicated: " + original + " " + sliceDescription(input)
                + (exact ? "; this exact content is recorded in the thread read ledger"
                    : "; this file was already read in full and recorded in the thread read ledger")
                + ", including compacted history. Reuse the earlier finding; use refresh=true only when exact source "
                + "text is genuinely required again.]");
        }
    }

    private static ToolResult rememberSuccessful(ToolResult result, String key, String wholeKey, String fingerprint,
        boolean completeFile, ToolContext ctx) {
        if (result == null || result.isError() || ctx == null || ctx.getThread() == null
            || ctx.getThread().getState() == null) return result;
        var state = ctx.getThread().getState();
        synchronized (state) {
            if (state.getFileReadCache() == null) state.setFileReadCache(new LinkedHashMap<>());
            CompressionState compression = state.getCompression();
            long generation = compression == null ? 0L : compression.getContextGeneration();
            Map<String, FileReadCacheEntry> cache = state.getFileReadCache();
            putReadEntry(cache, key, new FileReadCacheEntry(generation, fingerprint));
            if (completeFile) putReadEntry(cache, wholeKey, new FileReadCacheEntry(generation, fingerprint));
        }
        return result;
    }

    private static void putReadEntry(Map<String, FileReadCacheEntry> cache, String key, FileReadCacheEntry entry) {
        while (cache.size() >= MAX_READ_CACHE_ENTRIES && !cache.containsKey(key)) {
            String eldest = cache.keySet().iterator().next();
            cache.remove(eldest);
        }
        cache.put(key, entry);
    }

    private static String readCacheKey(Path original, Map<String, Object> input) throws IOException {
        String path = canonicalReadPath(original);
        Integer offset = asInt(input.get("offset"));
        Integer limit = asInt(input.get("limit"));
        if (offset != null || limit != null) {
            int off = offset == null ? 1 : Math.max(1, offset);
            int lim = limit == null ? MAX_DEFAULT_LINES : Math.max(1, Math.min(limit, MAX_DEFAULT_LINES));
            return path + "|lines:" + off + ":" + lim;
        }
        Object max = input.get("maxBytes");
        long maxBytes = max instanceof Number n ? Math.max(1L, Math.min(n.longValue(), ABSOLUTE_MAX_BYTES))
            : DEFAULT_MAX_BYTES;
        return path + "|bytes:" + maxBytes;
    }

    private static String wholeReadCacheKey(Path original) throws IOException {
        return canonicalReadPath(original) + "|whole";
    }

    private static String canonicalReadPath(Path original) throws IOException {
        String path = original.toRealPath().normalize().toString();
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            path = path.toLowerCase(Locale.ROOT);
        }
        return path;
    }

    private static String sliceDescription(Map<String, Object> input) {
        Integer offset = asInt(input.get("offset"));
        Integer limit = asInt(input.get("limit"));
        if (offset != null || limit != null) {
            return "lines " + (offset == null ? 1 : Math.max(1, offset)) + "+"
                + (limit == null ? MAX_DEFAULT_LINES : Math.max(1, Math.min(limit, MAX_DEFAULT_LINES)));
        }
        Object max = input.get("maxBytes");
        long maxBytes = max instanceof Number n ? Math.max(1L, Math.min(n.longValue(), ABSOLUTE_MAX_BYTES))
            : DEFAULT_MAX_BYTES;
        return "first " + maxBytes + " bytes";
    }

    private static String fingerprint(Path path, long size) throws Exception {
        String metadata = size + ":" + Files.getLastModifiedTime(path).toMillis();
        if (size > HASH_FINGERPRINT_MAX_BYTES) return metadata;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(path)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return metadata + ":" + HexFormat.of().formatHex(digest.digest());
    }

    /** Byte/char cap applied to on-demand extracted text: honour {@code maxBytes} (clamped) else the default. */
    private static long extractMaxBytes(Map<String, Object> input) {
        Object mb = input.get("maxBytes");
        if (mb instanceof Number n) {
            return Math.max(1L, Math.min(n.longValue(), ABSOLUTE_MAX_BYTES));
        }
        return DEFAULT_MAX_BYTES;
    }

    /**
     * Attempt on-demand text extraction for a document/image and persist the {@code .extracted.txt} sidecar (which this
     * tool reads preferentially, so the next read is instant). Returns the result on success, or {@code null} when
     * nothing extractable was produced so the caller can decide how to degrade. {@code mime} may be {@code null} → it
     * is detected here.
     */
    private ToolResult tryExtract(Path original, Path sidecar, String mime, Map<String, Object> input,
        ToolContext ctx) {
        if (extractor == null) {
            return null;
        }
        String m = mime != null ? mime : extractor.detectMime(original);
        if (!extractor.canExtract(m)) {
            return null;
        }
        String text = extractor.extractText(original, m);
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            Files.writeString(sidecar, text, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            /* sidecar is an optimisation; still return the text */
        }
        long cap = extractMaxBytes(input);
        String body = text;
        String suffix = "";
        if (body.length() > cap) {
            body = body.substring(0, (int)Math.min(body.length(), cap));
            suffix = "\n[... extracted text truncated to " + cap + " chars ...]";
        }
        track(original, ctx);
        return ToolResult.builder().content("[extracted text from " + original.getFileName() + "]\n" + body + suffix)
            .build();
    }

    private static ToolResult binaryRefusal(long size, Path original, Path sidecar) {
        return ToolResult.builder().error(true)
            .content(
                "refusing to read binary file (size=" + size + " bytes): " + original + (Files.isRegularFile(sidecar)
                    ? "; extracted sidecar was unreadable: " + sidecar : "; no extracted sidecar found")
                    + (original.getFileName().toString().matches("(?i).*\\.(png|jpe?g|webp|gif|bmp|tiff?|avif)$")
                        ? ". file_read reads text/OCR, not image appearance. Use image_inspect if it is offered, with uploaded/generated artifactIds (not this file path); otherwise use a model with image input support."
                        : ". Use a reader for this file format rather than retrying it as plain text."))
            .build();
    }

    private static boolean shouldExtractAsDocument(String mime, Path original) {
        if (mime == null) {
            return true;
        }
        if (!mime.startsWith("text/")) {
            return true;
        }
        String lower = original == null || original.getFileName() == null ? ""
            : original.getFileName().toString().toLowerCase(Locale.ROOT);
        return "text/rtf".equalsIgnoreCase(mime) || lower.endsWith(".rtf");
    }

    /**
     * UTF-8 encoded byte length of {@code s} computed per code point without allocating a byte array (unlike
     * {@code getBytes(UTF_8)}): 1 byte up to U+007F, 2 up to U+07FF, 3 for the rest of the BMP, 4 for supplementary
     * code points (surrogate pairs). Package-private for direct unit testing.
     */
    static long utf8Length(CharSequence s) {
        long bytes = 0;
        for (int i = 0, n = s.length(); i < n; i++) {
            char c = s.charAt(i);
            if (c <= 0x7F) {
                bytes += 1;
            } else if (c <= 0x7FF) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1))) {
                bytes += 4;
                i++; // consume the low surrogate of the pair
            } else {
                bytes += 3; // remaining BMP chars (unpaired surrogates counted as 3 — safe overestimate)
            }
        }
        return bytes;
    }

    private static Integer asInt(Object o) {
        if (o == null)
            return null;
        if (o instanceof Number n)
            return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String prefix(boolean usingSidecar, Path original, Path sidecar) {
        return usingSidecar
            ? "[using extracted text sidecar for " + original.getFileName() + ": " + sidecar.getFileName() + "]\n" : "";
    }

    private static boolean looksBinary(Path p, long size) {
        if (size == 0)
            return false;
        int probe = (int)Math.min(BINARY_PROBE, size);
        byte[] head = new byte[probe];
        try (var in = Files.newInputStream(p)) {
            int got = 0;
            while (got < probe) {
                int n = in.read(head, got, probe - got);
                if (n <= 0)
                    break;
                got += n;
            }
            for (int i = 0; i < got; i++) {
                if (head[i] == 0x00)
                    return true;
            }
            return false;
        } catch (Exception e) {
            // If we cannot probe, assume text — caller will surface IO errors next.
            return false;
        }
    }
}
