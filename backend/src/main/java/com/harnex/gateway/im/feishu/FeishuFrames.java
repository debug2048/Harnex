package com.harnex.gateway.im.feishu;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;

/** Feishu's Pbbp2 envelope. Protobuf handles wire decoding and preserves opaque routing fields in ACKs.
 * Schema: larksuite/oapi-sdk-java, ws/pb/Pbbp2.java (SeqID=1, LogID=2, service=3, method=4,
 * headers=5, payload_encoding=6, payload_type=7, payload=8). */
record FeishuFrames(UnknownFieldSet wire, Map<String, String> headers) {
    private static final int LIMIT = 2_000_000;
    static FeishuFrames decode(byte[] bytes) throws IOException {
        if (bytes.length > LIMIT) throw new IOException("oversized Feishu frame");
        var wire = UnknownFieldSet.parseFrom(bytes);
        for (int field = 1; field <= 4; field++) {
            if (wire.getField(field).getVarintList().size() != 1) throw new IOException("missing Feishu routing field");
        }
        var values = wire.getField(5).getLengthDelimitedList();
        if (values.size() > 64) throw new IOException("too many Feishu headers");
        Map<String, String> headers = new LinkedHashMap<>();
        for (ByteString bytesValue : values) {
            var header = UnknownFieldSet.parseFrom(bytesValue);
            String key = value(header, 1).toStringUtf8(), value = value(header, 2).toStringUtf8();
            if (key.isBlank() || key.length() > 64 || value.length() > 2048 || headers.putIfAbsent(key, value) != null)
                throw new IOException("invalid Feishu header");
        }
        return new FeishuFrames(wire, Map.copyOf(headers));
    }
    int method() { return wire.getField(4).getVarintList().get(0).intValue(); }
    String header(String key) { return headers.getOrDefault(key, ""); }
    byte[] payload() { return value(wire, 8).toByteArray(); }
    byte[] reply(byte[] payload, long elapsedMs) {
        return wire.toBuilder().clearField(8).addField(8, bytes(ByteString.copyFrom(payload)))
            .mergeField(5, bytes(headerBytes("biz_rt", String.valueOf(elapsedMs)))).build().toByteArray();
    }
    static byte[] ping(int service) {
        return UnknownFieldSet.newBuilder().addField(1, number(0)).addField(2, number(0))
            .addField(3, number(service)).addField(4, number(0))
            .addField(5, bytes(headerBytes("type", "ping"))).build().toByteArray();
    }
    private static ByteString headerBytes(String key, String value) {
        return UnknownFieldSet.newBuilder().addField(1, bytes(ByteString.copyFromUtf8(key)))
            .addField(2, bytes(ByteString.copyFromUtf8(value))).build().toByteString();
    }
    private static UnknownFieldSet.Field number(long value) { return UnknownFieldSet.Field.newBuilder().addVarint(value).build(); }
    private static UnknownFieldSet.Field bytes(ByteString value) { return UnknownFieldSet.Field.newBuilder().addLengthDelimited(value).build(); }
    private static ByteString value(UnknownFieldSet wire, int field) {
        var values = wire.getField(field).getLengthDelimitedList();
        return values.isEmpty() ? ByteString.EMPTY : values.get(0);
    }

    /** Fragment state belongs to one socket generation and cannot grow without bounds. */
    static final class Fragments {
        private record Parts(byte[][] pieces, long created, String trace) {}
        private final Map<String, Parts> pending = new HashMap<>();
        synchronized byte[] accept(FeishuFrames frame) throws IOException {
            int count = frame.header("sum").isEmpty() ? 1 : Integer.parseInt(frame.header("sum"));
            int index = frame.header("seq").isEmpty() ? 0 : Integer.parseInt(frame.header("seq"));
            if (count < 1 || count > 16 || index < 0 || index >= count) throw new IOException("invalid Feishu fragment index");
            if (count == 1) return frame.payload();
            long now = System.currentTimeMillis();
            pending.values().removeIf(value -> now - value.created > 60_000);
            String id = frame.header("message_id");
            if (id.isBlank() || !pending.containsKey(id) && pending.size() >= 8) throw new IOException("Feishu fragment limit reached");
            Parts parts = pending.computeIfAbsent(id, ignored -> new Parts(new byte[count][], now, frame.header("trace_id")));
            if (parts.pieces.length != count || !parts.trace.equals(frame.header("trace_id"))) throw new IOException("conflicting Feishu fragments");
            byte[] payload = frame.payload();
            if (parts.pieces[index] != null && !Arrays.equals(parts.pieces[index], payload)) throw new IOException("conflicting Feishu fragment");
            parts.pieces[index] = payload;
            int size = 0; boolean complete = true;
            for (byte[] piece : parts.pieces) { if (piece == null) complete = false; else size += piece.length; }
            if (size > LIMIT) { pending.remove(id); throw new IOException("oversized Feishu message"); }
            if (!complete) return null;
            pending.remove(id);
            var result = new ByteArrayOutputStream(size);
            for (byte[] piece : parts.pieces) result.writeBytes(piece);
            return result.toByteArray();
        }
        synchronized void clear() { pending.clear(); }
    }
}
