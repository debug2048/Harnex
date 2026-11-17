package com.harnex.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import com.fasterxml.jackson.databind.ObjectMapper;

/** File-adapter durability primitive. Callers own transaction/receipt semantics across multiple files. */
public final class AtomicJsonFiles {
    private AtomicJsonFiles() {}
    public static void write(ObjectMapper mapper, Path target, Object value) throws IOException {
        Files.createDirectories(target.getParent());
        byte[] bytes = mapper.writeValueAsBytes(value);
        Path temporary = Files.createTempFile(target.getParent(), ".pending-", ".json");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }
}
