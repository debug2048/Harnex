package com.harnex.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Recent control-plane diagnostics: two bounded JSONL files, independent of authoritative run/history state. */
public final class RotatingJsonlLog {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private RotatingJsonlLog() {}

    public static synchronized void append(Path file, String json) throws IOException {
        byte[] line = (json + "\n").getBytes(StandardCharsets.UTF_8);
        if (line.length > MAX_BYTES) return;
        Files.createDirectories(file.getParent());
        requireRegular(file);
        if (Files.exists(file) && Files.size(file) + line.length > MAX_BYTES) {
            Path backup = backup(file);
            requireRegular(backup);
            if (Files.size(file) > MAX_BYTES) {
                Files.write(backup, tail(file));
                Files.write(file, new byte[0]);
            } else Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.write(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public static synchronized List<String> recentLines(Path file) throws IOException {
        List<String> lines = new ArrayList<>();
        for (Path source : List.of(backup(file), file)) {
            if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) continue;
            requireRegular(source);
            new String(tail(source), StandardCharsets.UTF_8).lines().filter(line -> !line.isBlank()).forEach(lines::add);
        }
        return lines;
    }

    private static byte[] tail(Path file) throws IOException {
        long size = Files.size(file), start = Math.max(0, size - MAX_BYTES);
        ByteBuffer bytes = ByteBuffer.allocate((int)Math.min(size, MAX_BYTES));
        try (FileChannel input = FileChannel.open(file, StandardOpenOption.READ)) {
            input.position(start);
            while (bytes.hasRemaining() && input.read(bytes) >= 0) { }
        }
        int offset = 0;
        if (start > 0) while (offset < bytes.position() && bytes.array()[offset++] != '\n') { }
        return Arrays.copyOfRange(bytes.array(), offset, bytes.position());
    }

    private static Path backup(Path file) { return file.resolveSibling(file.getFileName() + ".1"); }
    private static void requireRegular(Path file) throws IOException {
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Diagnostic log must be a regular file");
        }
    }
}
