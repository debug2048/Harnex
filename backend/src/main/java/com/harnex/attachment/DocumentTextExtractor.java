package com.harnex.attachment;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.*;

import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;

import com.harnex.config.AgentConfig;
import com.harnex.process.ChildProcessEnvironment;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Shared document → plain-text extraction (Tika, with optional Tesseract OCR for images). Upload itself remains a
 * bounded storage/MIME-validation operation; {@code file_read} invokes this extractor lazily only when a model cannot
 * consume the original attachment and follows the local fallback path. The resulting sidecar makes subsequent reads
 * instant. Pure with respect to the workspace: this component only READS the input file; callers decide whether/where
 * to persist the result as {@code .extracted.txt}.
 *
 * <p>
 * Safety carried over verbatim: a bounded worker pool, a hard 50 MiB input cap (Tika's PDF/OOXML/ZIP parsers don't all
 * honour interruption, so we refuse oversize inputs up front), a 30 s parse timeout, and a 200 K-char output cap.
 */
@Slf4j
@Component
public class DocumentTextExtractor {

    /** Hard cap on extracted text (chars). */
    public static final int MAX_EXTRACTED_CHARS = 200_000;
    /** Hard ceiling on a single Tika parse — ZIP-bombs / hostile XML can otherwise hang a worker. */
    private static final long TIKA_PARSE_TIMEOUT_MS = 30_000L;
    /** Above this we refuse to even submit a parseable doc to Tika (interruption isn't always honoured). */
    public static final long MAX_PARSE_FILE_BYTES = 50L * 1024 * 1024; // 50 MiB

    private static final Set<String> PARSEABLE_PREFIXES = Set.of("application/pdf", "application/msword",
        "application/vnd.openxmlformats-officedocument", "application/vnd.ms-excel", "application/vnd.ms-powerpoint",
        "application/vnd.oasis.opendocument", "application/rtf", "text/", "message/rfc822");

    /** 4 workers + bounded queue so a couple of slow parses cannot lock out concurrent work. */
    private final ExecutorService tikaPool =
        new ThreadPoolExecutor(4, 4, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(8), r -> {
            Thread t = new Thread(r, "tika-extract");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());

    private final AgentConfig cfg;
    /** Cached probe: whether {@code tesseract} is on PATH. {@code null} = not yet probed. */
    private volatile Boolean tesseractAvailable;

    /**
     * Tika builds its detector/parser registry on construction and loads a large optional
     * document stack. Most GUI sessions never upload or inspect a binary document, so doing
     * that work while every workspace JVM starts adds avoidable seconds to every switch.
     * The holder is process-wide, thread-safe through class initialization, and initializes
     * only on the first MIME detection/extraction request.
     */
    private static final class TikaHolder {
        private static final Tika INSTANCE = new Tika();
    }

    public DocumentTextExtractor(AgentConfig cfg) {
        this.cfg = cfg;
    }

    /** Detect a file's MIME type by content (Tika). Returns null on I/O error. */
    public String detectMime(Path file) {
        try {
            return TikaHolder.INSTANCE.detect(file);
        } catch (IOException e) {
            return null;
        }
    }

    /** Stream variant shared by uploads so the application owns only one lazy Tika registry. */
    public String detectMime(InputStream input, String resourceName) {
        try {
            return TikaHolder.INSTANCE.detect(input, resourceName);
        } catch (IOException e) {
            return null;
        }
    }

    /** True if {@code file_read} should attempt on-demand extraction for this MIME (doc, or image with OCR usable). */
    public boolean canExtract(String mime) {
        return isParseable(mime) || (isImage(mime) && ocrUsable());
    }

    /**
     * Shut down the shared worker pool on context teardown so an in-flight parse can't hang {@code docker stop} for ~30
     * s. Idempotent.
     */
    @PreDestroy
    public void close() {
        tikaPool.shutdownNow();
        try {
            if (!tikaPool.awaitTermination(3, TimeUnit.SECONDS)) {
                log.warn("Tika pool did not terminate within 3s");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** Result of an extraction attempt. {@code text}: null = not attempted/skipped/failed; "" = parsed but empty. */
    public record Extraction(String text, String note) {
    }

    /** True for document MIME types Tika can turn into text (PDF / Office / OpenDocument / RTF / text / email). */
    public boolean isParseable(String mime) {
        return mime != null && PARSEABLE_PREFIXES.stream().anyMatch(mime::startsWith);
    }

    public boolean isImage(String mime) {
        return mime != null && mime.startsWith("image/");
    }

    /** Whether image OCR is both enabled in config AND backed by an available {@code tesseract} binary. */
    public boolean ocrUsable() {
        return cfg.getVision() != null && cfg.getVision().isOcrFallback() && tesseractAvailable();
    }

    /**
     * Convenience for {@code file_read}: just the extracted text. Returns {@code null} when nothing was extractable and
     * {@code ""} when the doc parsed to empty text (so the caller can distinguish "couldn't parse" from "parsed,
     * empty").
     */
    public String extractText(Path file, String mime) {
        return extract(file, mime).text();
    }

    /**
     * Full bounded extraction used by lazy file reads. Sidecar writing and preview construction belong to callers.
     */
    public Extraction extract(Path file, String mime) {
        boolean image = isImage(mime);
        boolean ocrEnabled = cfg.getVision() != null && cfg.getVision().isOcrFallback();
        boolean parseableDoc = isParseable(mime);
        boolean attemptOcr = image && ocrEnabled && tesseractAvailable();

        if (!parseableDoc && !attemptOcr) {
            String note;
            if (image && ocrEnabled && !tesseractAvailable()) {
                note = "image stored as-is; vision required (install tesseract for OCR fallback)";
            } else if (image) {
                note = "image stored as-is; vision required (or set harnex.vision.ocr-fallback=true)";
            } else {
                note = null;
            }
            return new Extraction(null, note);
        }
        if (parseableDoc) {
            long sz = sizeOrZero(file);
            if (sz > MAX_PARSE_FILE_BYTES) {
                log.info("Tika extract skipped: {} is {} bytes (> {} cap)", file.getFileName(), sz,
                    MAX_PARSE_FILE_BYTES);
                return new Extraction(null, "extraction skipped: file exceeds "
                    + (MAX_PARSE_FILE_BYTES / (1024 * 1024)) + " MiB inline-extract cap");
            }
        }
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            BodyContentHandler handler = new BodyContentHandler(MAX_EXTRACTED_CHARS);
            Metadata md = new Metadata();
            md.set(TikaCoreProperties.RESOURCE_NAME_KEY, file.getFileName().toString());
            ParseContext pctx = new ParseContext();
            if (attemptOcr) {
                TesseractOCRConfig oc = new TesseractOCRConfig();
                oc.setSkipOcr(false);
                oc.setLanguage(cfg.getVision().getOcrLanguage());
                oc.setTimeoutSeconds(
                    (int)Math.min(Integer.MAX_VALUE, Math.max(5, cfg.getVision().getOcrTimeoutMs() / 1000)));
                pctx.set(TesseractOCRConfig.class, oc);
            }
            Future<?> fut;
            try {
                fut = tikaPool.submit(() -> {
                    try {
                        new AutoDetectParser().parse(in, handler, md, pctx);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            } catch (RejectedExecutionException ree) {
                log.warn("Tika pool saturated, skipping extraction for {}", file.getFileName());
                return new Extraction(null, "extraction skipped: server busy");
            }
            try {
                fut.get(TIKA_PARSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                fut.cancel(true);
                log.warn("Tika parse timeout after {}ms for {}", TIKA_PARSE_TIMEOUT_MS, file.getFileName());
                return new Extraction(null, "parse timeout");
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause() == null ? ee : ee.getCause();
                if (cause.getCause() != null) {
                    cause = cause.getCause();
                }
                String fallback = rtfFallback(file, mime);
                if (fallback != null && !fallback.isBlank()) {
                    return new Extraction(fallback, "RTF fallback");
                }
                log.debug("Tika extract failed for {}: {}", file.getFileName(), cause.toString());
                return new Extraction(null, "parse error: " + cause.getClass().getSimpleName());
            } catch (InterruptedException ie) {
                fut.cancel(true);
                Thread.currentThread().interrupt();
                return new Extraction(null, "interrupted");
            }
            String text = handler.toString();
            if (text == null) {
                text = "";
            }
            String normalised = text.replaceAll("[ \\t\\f]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
            if (normalised.isEmpty()) {
                String fallback = rtfFallback(file, mime);
                if (fallback != null && !fallback.isBlank()) {
                    return new Extraction(fallback, "RTF fallback");
                }
                return new Extraction("", attemptOcr ? "OCR yielded no text" : "no extractable text");
            }
            return new Extraction(normalised, attemptOcr ? "OCR via tesseract" : null);
        } catch (IOException e) {
            log.debug("Tika extract failed for {}: {}", file.getFileName(), e.toString());
            return new Extraction(null, "parse error: " + e.getClass().getSimpleName());
        }
    }

    private static String rtfFallback(Path file, String mime) {
        if (!isRtfLike(file, mime) || sizeOrZero(file) > MAX_PARSE_FILE_BYTES) {
            return null;
        }
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            String text = raw.replaceAll("\\\\'([0-9a-fA-F]{2})", " ").replaceAll("\\\\([{}\\\\])", "$1")
                .replaceAll("\\\\[a-zA-Z]+-?\\d* ?", " ").replace('{', ' ').replace('}', ' ')
                .replaceAll("[ \\t\\f]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
            return text.isBlank() ? null : text;
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isRtfLike(Path file, String mime) {
        if (mime != null) {
            String m = mime.toLowerCase(Locale.ROOT);
            if (m.equals("application/rtf") || m.equals("text/rtf") || m.equals("application/x-rtf")) {
                return true;
            }
        }
        String name =
            file == null || file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".rtf");
    }

    private static long sizeOrZero(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * Probe whether {@code tesseract} is on PATH. Cached for the JVM lifetime (restart if you install tesseract later).
     */
    private boolean tesseractAvailable() {
        Boolean cached = tesseractAvailable;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (tesseractAvailable != null) {
                return tesseractAvailable;
            }
            boolean found = false;
            Process p = null;
            try {
                ProcessBuilder pb = new ProcessBuilder(System.getProperty("os.name", "").toLowerCase().contains("win")
                    ? new String[] {"cmd", "/c", "where", "tesseract"}
                    : new String[] {"sh", "-c", "command -v tesseract"});
                pb.redirectErrorStream(true);
                p = ChildProcessEnvironment.start(pb);
                if (p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    found = true;
                }
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            } finally {
                if (p != null) {
                    p.destroyForcibly();
                }
            }
            tesseractAvailable = found;
            return found;
        }
    }
}
