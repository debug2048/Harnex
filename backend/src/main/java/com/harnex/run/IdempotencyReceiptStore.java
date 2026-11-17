package com.harnex.run;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.harnex.config.AgentConfig;
import com.harnex.run.RunLedger.RunPersistenceException;

/** Workspace-local persistent idempotency receipts keyed by operation, target and client request id. */
@Service
public class IdempotencyReceiptStore {

    private static final ObjectMapper M = JsonMapper.builder()
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .build();

    private final Path root;
    private final Map<String, IdempotencyReceiptRecord> memory;

    @Autowired
    public IdempotencyReceiptStore(AgentConfig cfg) {
        this(Path.of(cfg.getDataDir()).toAbsolutePath().normalize().resolve("runs/receipts"));
    }

    public IdempotencyReceiptStore(Path root) {
        this.root = root == null ? null : root.toAbsolutePath().normalize();
        this.memory = root == null ? new ConcurrentHashMap<>() : null;
    }

    static IdempotencyReceiptStore inMemory() {
        return new IdempotencyReceiptStore((Path)null);
    }

    public synchronized Optional<IdempotencyReceiptRecord> find(String operation, String targetId, String key) {
        String scope = scope(operation, targetId);
        if (blank(key)) return Optional.empty();
        String storageKey = storageKey(scope, key);
        if (memory != null) return Optional.ofNullable(memory.get(storageKey));
        Path file = path(scope, key);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            IdempotencyReceiptRecord record = M.readValue(file.toFile(), IdempotencyReceiptRecord.class).validated();
            if (!scope.equals(record.scope()) || !key.equals(record.key())) {
                throw new IllegalArgumentException("idempotency receipt path/identity mismatch");
            }
            return Optional.of(record);
        } catch (IOException | IllegalArgumentException failure) {
            throw new RunPersistenceException("idempotency receipt read failed", failure);
        }
    }

    public synchronized IdempotencyReceiptRecord commit(String operation, String targetId, String key,
        String requestHash, Map<String, Object> result, String resourceId, long committedRevision) {
        String scope = scope(operation, targetId);
        IdempotencyReceiptRecord existing = find(operation, targetId, key).orElse(null);
        if (existing != null) {
            if (!existing.requestHash().equals(requestHash)) {
                throw new IdempotencyConflictException(existing);
            }
            return existing;
        }
        IdempotencyReceiptRecord created = new IdempotencyReceiptRecord(
            IdempotencyReceiptRecord.CURRENT_SCHEMA_VERSION,
            scope,
            key,
            requestHash,
            result,
            resourceId,
            committedRevision,
            System.currentTimeMillis()).validated();
        persist(created);
        return created;
    }

    public static String requestHash(Object request) {
        try {
            return hex(sha256(M.writeValueAsBytes(request)));
        } catch (IOException failure) {
            throw new IllegalArgumentException("unable to hash idempotent request", failure);
        }
    }

    /** Remove every receipt in one operation/target scope when its owning resource is deleted. */
    public synchronized void purgeScope(String operation, String targetId) {
        String scope = scope(operation, targetId);
        if (memory != null) {
            String prefix = scope + '\u0000';
            memory.keySet().removeIf(key -> key.startsWith(prefix));
            return;
        }
        Path directory = root.resolve(hex(sha256(scope.getBytes(StandardCharsets.UTF_8))));
        if (!Files.isDirectory(directory)) return;
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.toList()) Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        } catch (IOException failure) {
            throw new RunPersistenceException("idempotency receipt purge failed", failure);
        }
    }

    private void persist(IdempotencyReceiptRecord record) {
        String storageKey = storageKey(record.scope(), record.key());
        if (memory != null) {
            memory.put(storageKey, record);
            return;
        }
        Path target = pathForScope(record.scope(), record.key());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            M.writeValue(tmp.toFile(), record);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new RunPersistenceException("idempotency receipt persist failed", failure);
        }
    }

    private Path path(String scope, String key) {
        return pathForScope(scope, key);
    }

    private Path pathForScope(String scope, String key) {
        return root.resolve(hex(sha256(scope.getBytes(StandardCharsets.UTF_8))))
            .resolve(hex(sha256(key.getBytes(StandardCharsets.UTF_8))) + ".json");
    }

    private static String storageKey(String scope, String key) {
        return scope + '\u0000' + key;
    }

    private static String scope(String operation, String targetId) {
        if (blank(operation) || blank(targetId)) {
            throw new IllegalArgumentException("idempotency operation and target are required");
        }
        return operation + ":" + targetId;
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String hex(byte[] value) {
        return HexFormat.of().formatHex(value);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public static final class IdempotencyConflictException extends IllegalStateException {
        private final IdempotencyReceiptRecord existing;

        IdempotencyConflictException(IdempotencyReceiptRecord existing) {
            super("idempotency key was already used with a different payload");
            this.existing = existing;
        }

        public IdempotencyReceiptRecord existing() {
            return existing;
        }
    }
}
