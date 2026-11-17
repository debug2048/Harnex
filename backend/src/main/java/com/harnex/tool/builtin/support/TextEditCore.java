package com.harnex.tool.builtin.support;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.IntStream;

import com.harnex.process.ShellPaths;

/**
 * Reusable text-edit core shared by {@code file_edit} (single hunk) and {@code multi_edit} (ordered hunks applied to
 * one in-memory buffer). Extracted verbatim from the original {@code FileEditTool} private logic so behaviour — and
 * every error string — is identical; {@code FileEditTool} now delegates here.
 *
 * <h2>Three-tier matching</h2>
 * <ol>
 * <li><b>Exact</b> literal match of {@code oldStr}.</li>
 * <li><b>EOL-tolerant</b> — re-encode the needle to the buffer's dominant line ending (handles "model emitted LF but
 * the file is CRLF").</li>
 * <li><b>Whitespace-tolerant</b> — normalise CRLF→LF and strip per-line trailing whitespace on both sides, locate the
 * needle in the normalised view, then splice the replacement back into the ORIGINAL text via an offset map so CRLF /
 * trailing whitespace OUTSIDE the matched region survive untouched.</li>
 * </ol>
 *
 * All matching is pure (buffer in → {@link HunkResult} out); {@link #atomicWrite} is the only side-effecting method.
 */
public final class TextEditCore {

    private TextEditCore() {}

    // Bounded locks coordinate edits across runs, including calls without a snapshot context.
    private static final Object[] WRITE_LOCKS = IntStream.range(0, 128)
        .mapToObj(i -> new Object()).toArray();
    private static final int WINDOWS_MOVE_RETRIES = 8;

    /**
     * Outcome of applying a single hunk to a buffer. On success {@link #text} is the new buffer; on failure
     * {@link #error} carries the model-facing message and {@link #text} is null.
     */
    public record HunkResult(boolean ok, String text, int replacements, boolean whitespaceTolerant, String error) {

        public static HunkResult ok(String text, int replacements, boolean whitespaceTolerant) {
            return new HunkResult(true, text, replacements, whitespaceTolerant, null);
        }

        public static HunkResult fail(String error) {
            return new HunkResult(false, null, 0, false, error);
        }
    }

    /**
     * Apply one find/replace to {@code buffer}. Does not touch disk.
     *
     * @param buffer current text
     * @param oldStr needle (caller must ensure non-empty)
     * @param newStr replacement (inserted verbatim; the model owns its own EOLs in the non-whitespace tiers)
     * @param replaceAll replace every occurrence instead of requiring a unique match
     */
    public static HunkResult applyHunk(String buffer, String oldStr, String newStr, boolean replaceAll) {
        return applyHunk(buffer, oldStr, newStr, replaceAll, true);
    }

    /** Without a current tracked read, only literal/EOL matches may establish the edit's context. */
    public static HunkResult applyHunk(String buffer, String oldStr, String newStr, boolean replaceAll,
        boolean allowWhitespaceTolerance) {
        String s = buffer;
        // Detect the file's dominant line ending so we can preserve it on write.
        boolean crlf = s.contains("\r\n");
        int n = countOccurrences(s, oldStr);
        String matchedOld = oldStr;
        if (n == 0) {
            // Tier 1: line-ending tolerance. Re-encode the needle with the file's actual EOL.
            String needleEol = crlf ? toCrlf(oldStr) : toLf(oldStr);
            if (!needleEol.equals(oldStr)) {
                int n1 = countOccurrences(s, needleEol);
                if (n1 > 0) {
                    if (!replaceAll && n1 > 1) {
                        return HunkResult.fail("oldStr matches " + n1
                            + " times after EOL normalisation; set replaceAll=true or include more context");
                    }
                    matchedOld = needleEol;
                    n = n1;
                }
            }
            if (n == 0) {
                if (!allowWhitespaceTolerance) {
                    return HunkResult.fail("oldStr not found with exact/EOL matching "
                        + "(whitespace tolerance requires a current tracked read)");
                }
                // Tier 2: full whitespace tolerance with an offset map back into the original.
                NormResult nh = normaliseWsWithMap(s);
                String normNeedle = normaliseWs(oldStr);
                int n2 = countOccurrences(nh.text(), normNeedle);
                if (n2 == 0) {
                    return HunkResult.fail("oldStr not found (also tried whitespace-normalised match)");
                }
                if (!replaceAll && n2 > 1) {
                    return HunkResult.fail("oldStr matches " + n2
                        + " times after whitespace normalisation; set replaceAll=true or include more context");
                }
                StringBuilder out = new StringBuilder(s.length());
                int searchFrom = 0;
                int origCursor = 0;
                int replaced = 0;
                while (true) {
                    int hit = nh.text().indexOf(normNeedle, searchFrom);
                    if (hit < 0) {
                        break;
                    }
                    int origStart = nh.indexMap()[hit];
                    int normEnd = hit + normNeedle.length();
                    int origEnd = normEnd < nh.indexMap().length ? nh.indexMap()[normEnd] : s.length();
                    String repl = crlf ? toCrlf(newStr) : toLf(newStr);
                    out.append(s, origCursor, origStart).append(repl);
                    origCursor = origEnd;
                    searchFrom = normEnd;
                    replaced++;
                    if (!replaceAll) {
                        break;
                    }
                }
                out.append(s, origCursor, s.length());
                return HunkResult.ok(out.toString(), replaced, true);
            }
        } else if (!replaceAll && n > 1) {
            return HunkResult.fail("oldStr matches " + n + " times; set replaceAll=true or include more context");
        }
        String replaced = replaceAll ? s.replace(matchedOld, newStr) : replaceFirstLiteral(s, matchedOld, newStr);
        return HunkResult.ok(replaced, n, false);
    }

    /** Atomic write via tmp + ATOMIC_MOVE so readers never see a half-written file. Preserves POSIX perms. */
    public static void atomicWrite(Path p, String content) throws IOException {
        atomicWrite(p, content, null);
    }

    /** Stage the replacement, then compare current contents immediately before promoting it. */
    public static void atomicWrite(Path p, String content, String expectedContent) throws IOException {
        synchronized (WRITE_LOCKS[Math.floorMod(p.toAbsolutePath().normalize().hashCode(), WRITE_LOCKS.length)]) {
            atomicWriteLocked(p, content, expectedContent);
        }
    }

    private static void atomicWriteLocked(Path p, String content, String expectedContent) throws IOException {
        Set<PosixFilePermission> origPerms = null;
        if (Files.exists(p) && p.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            try {
                origPerms = Files.getPosixFilePermissions(p);
            } catch (Exception ignored) {
                /* best effort */ }
        }
        // createTempFile uses O_CREAT|O_EXCL so a pre-planted "<name>.tmp" symlink cannot be followed
        // out of the sandbox — a fixed sibling name would be a symlink-attack vector.
        Path tmp = Files.createTempFile(p.getParent(), p.getFileName().toString() + ".", ".tmp");
        boolean promoted = false;
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            replaceStaged(tmp, p, expectedContent);
            promoted = true;
        } finally {
            if (!promoted) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (Exception ignored) {
                    /* best effort */ }
            }
        }
        if (origPerms != null) {
            try {
                Files.setPosixFilePermissions(p, origPerms);
            } catch (Exception ignored) {
                /* best effort */ }
        }
    }

    private static void replaceStaged(Path tmp, Path p, String expectedContent) throws IOException {
        boolean atomic = true;
        int retries = 0;
        for (;;) {
            // A reader/indexer may briefly deny deletion on Windows. Recheck the
            // edit precondition on every attempt, since another writer can act while we wait.
            if (expectedContent != null) {
                boolean unchanged = false;
                if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                    byte[] expected = expectedContent.getBytes(StandardCharsets.UTF_8);
                    try (var in = Files.newInputStream(p, LinkOption.NOFOLLOW_LINKS)) {
                        // Bound the read even if another writer has replaced the file with a huge one.
                        unchanged = Arrays.equals(expected, in.readNBytes(expected.length + 1));
                    }
                }
                if (!unchanged) {
                    throw new IOException("refusing edit: file changed while preparing the edit; no changes written. "
                        + "Call file_read on " + p + " and retry against the current contents.");
                }
            }
            try {
                if (atomic) Files.move(tmp, p, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                else Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AtomicMoveNotSupportedException e) {
                if (!atomic) throw e;
                atomic = false;
            } catch (FileSystemException failure) {
                // Java maps Windows sharing errors to AccessDeniedException or a
                // plain FileSystemException. Never delete/truncate the destination
                // to work around a lock, and let permanent failures surface after a bounded wait.
                if (!ShellPaths.isWindows()
                    || !(failure instanceof AccessDeniedException || failure.getClass() == FileSystemException.class)
                    || retries >= WINDOWS_MOVE_RETRIES) throw failure;
                try {
                    Thread.sleep(Math.min(25L << retries++, 200L));
                } catch (InterruptedException cancellation) {
                    Thread.currentThread().interrupt();
                    var interrupted = new InterruptedIOException("Interrupted while replacing " + p);
                    interrupted.initCause(cancellation);
                    interrupted.addSuppressed(failure);
                    throw interrupted;
                }
            }
        }
    }

    // ---- pure helpers (moved verbatim from FileEditTool) -----------------------------------------

    private static String toLf(String s) {
        return s.replace("\r\n", "\n").replace("\r", "\n");
    }

    private static String toCrlf(String s) {
        return toLf(s).replace("\n", "\r\n");
    }

    private static int countOccurrences(String hay, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        int c = 0, i = 0;
        while ((i = hay.indexOf(needle, i)) != -1) {
            c++;
            i += needle.length();
        }
        return c;
    }

    private static String replaceFirstLiteral(String hay, String needle, String repl) {
        int i = hay.indexOf(needle);
        if (i < 0) {
            return hay;
        }
        return hay.substring(0, i) + repl + hay.substring(i + needle.length());
    }

    /** Normalise CRLF→LF and strip trailing whitespace on each line. */
    private static String normaliseWs(String s) {
        String[] lines = s.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            int end = l.length();
            while (end > 0 && (l.charAt(end - 1) == ' ' || l.charAt(end - 1) == '\t')) {
                end--;
            }
            sb.append(l, 0, end);
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Same normalisation as {@link #normaliseWs} but also returns an index map: for every offset {@code i} in the
     * normalised string, {@code indexMap[i]} is the corresponding offset in the original. The array length is
     * {@code normalised.length() + 1} (sentinel = original length).
     */
    private static NormResult normaliseWsWithMap(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        int[] map = new int[s.length() + 1];
        int origLen = s.length();
        int i = 0;
        int n = origLen;
        while (i < n) {
            char ch = s.charAt(i);
            if (ch == '\r') {
                map[sb.length()] = i;
                sb.append('\n');
                i = (i + 1 < n && s.charAt(i + 1) == '\n') ? i + 2 : i + 1;
            } else if (ch == '\n') {
                while (sb.length() > 0) {
                    char last = sb.charAt(sb.length() - 1);
                    if (last == ' ' || last == '\t') {
                        sb.setLength(sb.length() - 1);
                    } else {
                        break;
                    }
                }
                map[sb.length()] = i;
                sb.append('\n');
                i++;
            } else {
                map[sb.length()] = i;
                sb.append(ch);
                i++;
            }
        }
        while (sb.length() > 0) {
            char last = sb.charAt(sb.length() - 1);
            if (last == ' ' || last == '\t') {
                sb.setLength(sb.length() - 1);
            } else {
                break;
            }
        }
        int[] tight = new int[sb.length() + 1];
        System.arraycopy(map, 0, tight, 0, sb.length());
        tight[sb.length()] = origLen;
        return new NormResult(sb.toString(), tight);
    }

    private record NormResult(String text, int[] indexMap) {
    }
}
