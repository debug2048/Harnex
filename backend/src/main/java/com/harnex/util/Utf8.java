package com.harnex.util;

/**
 * UTF-8 byte-array helpers. The single use case today is "I have a byte buffer that may have been truncated mid
 * code-point — give me a length that ends on a complete UTF-8 sequence so {@code new String(buf, 0, len, UTF_8)}
 * doesn't silently emit a U+FFFD replacement char."
 *
 * <p>
 * Used by {@link com.harnex.tool.builtin.FileReadTool} and {@link com.harnex.tool.builtin.WebFetchTool} when capping
 * output at a byte budget.
 *
 * <p>
 * UTF-8 layout reminder:
 * 
 * <pre>
 *   0xxxxxxx                                          → 1 byte  (ASCII)
 *   110xxxxx 10xxxxxx                                 → 2 bytes
 *   1110xxxx 10xxxxxx 10xxxxxx                        → 3 bytes
 *   11110xxx 10xxxxxx 10xxxxxx 10xxxxxx               → 4 bytes
 * </pre>
 */
public final class Utf8 {
    private Utf8() {}

    /**
     * Return the largest {@code n <= len} such that {@code buf[0..n)} ends on a complete UTF-8 code point. If the
     * buffer is empty or the trailing bytes are already complete, returns {@code len} unchanged. If the lead byte
     * itself is invalid, returns {@code len} unchanged (caller's decoder will substitute U+FFFD for the bad byte — no
     * worse than today).
     */
    public static int safeEnd(byte[] buf, int len) {
        if (buf == null || len <= 0)
            return Math.max(0, len);
        if (len > buf.length)
            len = buf.length;
        // Walk back up to 3 bytes looking for the start of the last code point.
        int i = len - 1;
        int continuations = 0;
        while (i > 0 && (buf[i] & 0xC0) == 0x80 && continuations < 3) {
            i--;
            continuations++;
        }
        byte lead = buf[i];
        int expected;
        if ((lead & 0x80) == 0)
            expected = 1;
        else if ((lead & 0xE0) == 0xC0)
            expected = 2;
        else if ((lead & 0xF0) == 0xE0)
            expected = 3;
        else if ((lead & 0xF8) == 0xF0)
            expected = 4;
        else
            return len; // not a valid lead — give up
        int actual = len - i;
        if (actual >= expected)
            return len; // last sequence is complete
        return i; // truncate *before* the incomplete lead
    }
}
