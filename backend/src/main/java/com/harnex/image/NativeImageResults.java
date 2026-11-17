package com.harnex.image;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.execution.RunExecutionContext;
import com.harnex.net.HttpExchangeState;

/** Validate native terminal metadata before releasing images. Thought previews never become user artifacts. */
final class NativeImageResults {
    private static final JsonFactory JSON = new JsonFactory();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_RESULTS = 16;
    private static final long MAX_BINARY_BYTES = 64L * 1024 * 1024;

    static ImageProvider.Output gemini(InputStream stream, RunExecutionContext context, HttpExchangeState exchange,
        ImageProvider.ResultSink sink) throws IOException {
        String handle = null;
        boolean blocked = false, complete = true, sawCandidates = false;
        Map<String, Long> usage = new LinkedHashMap<>();
        List<Path> results = new ArrayList<>();
        try (Spool spool = new Spool(); JsonParser parser = JSON.createParser(stream)) {
            require(parser.nextToken(), JsonToken.START_OBJECT);
            while (next(parser) != JsonToken.END_OBJECT) {
                String field = parser.currentName(); next(parser);
                switch (field) {
                    case "responseId" -> handle = text(parser, 256);
                    case "error" -> { if (parser.currentToken() != JsonToken.VALUE_NULL) blocked = true; parser.skipChildren(); }
                    case "promptFeedback" -> {
                        if (parser.currentToken() == JsonToken.START_OBJECT) {
                            while (next(parser) != JsonToken.END_OBJECT) {
                                String key = parser.currentName(); next(parser);
                                if ("blockReason".equals(key)) blocked |= parser.currentToken() == JsonToken.VALUE_STRING
                                    && !"BLOCK_REASON_UNSPECIFIED".equals(text(parser, 128));
                                else parser.skipChildren();
                            }
                        } else parser.skipChildren();
                    }
                    case "usageMetadata" -> geminiUsage(parser, usage);
                    case "candidates" -> {
                        require(parser.currentToken(), JsonToken.START_ARRAY); sawCandidates = true;
                        int candidates = 0;
                        while (next(parser) != JsonToken.END_ARRAY) {
                            if (++candidates > MAX_RESULTS) throw new IOException("too many native image candidates");
                            require(parser.currentToken(), JsonToken.START_OBJECT);
                            String finish = null; List<Path> candidate = new ArrayList<>();
                            while (next(parser) != JsonToken.END_OBJECT) {
                                String key = parser.currentName(); next(parser);
                                if ("finishReason".equals(key)) finish = text(parser, 128);
                                else if ("content".equals(key)) readContent(parser, candidate, spool, context);
                                else parser.skipChildren();
                            }
                            if ("STOP".equals(finish)) results.addAll(candidate);
                            else if (finish == null || "MAX_TOKENS".equals(finish) || "OTHER".equals(finish)) complete = false;
                            // Safety/refusal endings are terminal, but none of their images may be published.
                        }
                    }
                    default -> parser.skipChildren();
                }
            }
            if (parser.nextToken() != null) throw new IOException("trailing native image response data");
            if (!sawCandidates && !blocked) throw new IOException("missing Gemini candidates");
            if (!blocked) {
                int index = 0;
                for (Path result : results) {
                    context.checkActive();
                    sink.receive(index++, output -> { try (InputStream bytes = Files.newInputStream(result)) { bytes.transferTo(output); } });
                    exchange.outputReceived();
                }
            }
        }
        return new ImageProvider.Output(handle, usage, blocked || complete);
    }

    private static void readContent(JsonParser parser, List<Path> images, Spool spool, RunExecutionContext context) throws IOException {
        require(parser.currentToken(), JsonToken.START_OBJECT);
        while (next(parser) != JsonToken.END_OBJECT) {
            String field = parser.currentName(); next(parser);
            if (!"parts".equals(field)) { parser.skipChildren(); continue; }
            require(parser.currentToken(), JsonToken.START_ARRAY);
            int parts = 0;
            while (next(parser) != JsonToken.END_ARRAY) {
                context.checkActive();
                if (++parts > 256) throw new IOException("too many Gemini parts");
                require(parser.currentToken(), JsonToken.START_OBJECT);
                boolean thought = false; Path image = null; String mime = null;
                while (next(parser) != JsonToken.END_OBJECT) {
                    String key = parser.currentName(); next(parser);
                    if ("thought".equals(key)) {
                        if (parser.currentToken() != JsonToken.VALUE_TRUE && parser.currentToken() != JsonToken.VALUE_FALSE) {
                            throw new IOException("invalid thought flag");
                        }
                        thought = parser.getBooleanValue();
                    }
                    else if ("inlineData".equals(key) || "inline_data".equals(key)) {
                        require(parser.currentToken(), JsonToken.START_OBJECT);
                        while (next(parser) != JsonToken.END_OBJECT) {
                            String dataField = parser.currentName(); next(parser);
                            if ("mimeType".equals(dataField) || "mime_type".equals(dataField)) mime = text(parser, 128);
                            else if ("data".equals(dataField)) {
                                if (image != null) throw new IOException("duplicate inline image data");
                                require(parser.currentToken(), JsonToken.VALUE_STRING); image = spool.decode(parser);
                            } else parser.skipChildren();
                        }
                    } else parser.skipChildren();
                }
                if (image != null && !thought) {
                    if (!"image/png".equals(mime)) throw new IOException("unsupported Gemini output MIME type");
                    images.add(image);
                }
            }
        }
    }

    private static void geminiUsage(JsonParser parser, Map<String, Long> usage) throws IOException {
        require(parser.currentToken(), JsonToken.START_OBJECT);
        long visible = 0, thinking = 0;
        while (next(parser) != JsonToken.END_OBJECT) {
            String key = parser.currentName(); next(parser);
            if (!parser.currentToken().isNumeric()) { parser.skipChildren(); continue; }
            long value = Math.max(0, parser.getLongValue());
            switch (key) {
                case "promptTokenCount" -> usage.put("input_tokens", value);
                case "candidatesTokenCount" -> visible = value;
                case "thoughtsTokenCount" -> thinking = value;
                case "cachedContentTokenCount" -> usage.put("input_tokens_details.cached_tokens", value);
                case "totalTokenCount" -> usage.put("total_tokens", value);
                default -> { }
            }
        }
        usage.put("output_tokens", Math.addExact(visible, thinking));
        usage.put("output_tokens_details.reasoning_tokens", thinking);
    }

    static ImageProvider.Output dashscope(InputStream stream, ImageProvider.Input input, RunExecutionContext context,
        HttpExchangeState exchange, ImageProvider.ResultSink sink, ImageHttpTransport transport) throws IOException {
        byte[] bytes = stream.readNBytes(1024 * 1024 + 1);
        if (bytes.length > 1024 * 1024) throw new IOException("DashScope image metadata too large");
        var root = MAPPER.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);
        if (root == null || !root.isObject()) throw new IOException("invalid DashScope response");
        String handle = root.path("request_id").asText(null);
        if (handle != null && handle.length() > 256) throw new IOException("image response ID too large");
        if (root.hasNonNull("code") || root.hasNonNull("error")) return new ImageProvider.Output(handle, Map.of(), true);
        var choices = root.path("output").path("choices");
        if (!choices.isArray()) throw new IOException("missing DashScope choices");
        List<String> urls = new ArrayList<>(); boolean complete = true;
        for (var choice : choices) {
            if (!"stop".equals(choice.path("finish_reason").asText())) { complete = false; continue; }
            var content = choice.path("message").path("content");
            if (!content.isArray()) throw new IOException("invalid DashScope content");
            for (var part : content) if (part.path("image").isTextual()) {
                String url = part.path("image").asText();
                if (url.length() > 8192 || urls.size() >= MAX_RESULTS) throw new IOException("DashScope result limit exceeded");
                urls.add(url);
            }
        }
        Map<String, Long> usage = new LinkedHashMap<>();
        for (String field : List.of("image_count", "width", "height", "input_image_count", "output_image_count", "output_width", "output_height")) {
            if (root.path("usage").path(field).isIntegralNumber()) usage.put(field, Math.max(0, root.path("usage").path(field).asLong()));
        }
        for (int i = 0; i < urls.size(); i++) {
            context.checkActive(); String url = urls.get(i);
            sink.receive(i, output -> transport.download(input, url, context, output)); exchange.outputReceived();
        }
        return new ImageProvider.Output(handle, usage, complete);
    }

    private static JsonToken next(JsonParser parser) throws IOException {
        JsonToken token = parser.nextToken(); if (token == null) throw new IOException("incomplete native image response"); return token;
    }
    private static void require(JsonToken actual, JsonToken expected) throws IOException {
        if (actual != expected) throw new IOException("invalid native image response structure");
    }
    private static String text(JsonParser parser, int limit) throws IOException {
        require(parser.currentToken(), JsonToken.VALUE_STRING);
        String value = parser.getText(); if (value.length() > limit) throw new IOException("image metadata too large"); return value;
    }
    private static final class Spool implements AutoCloseable {
        private final List<Path> files = new ArrayList<>();
        private long bytes;
        Path decode(JsonParser parser) throws IOException {
            if (files.size() >= MAX_RESULTS) throw new IOException("too many inline image results");
            Path file = Files.createTempFile("harnex-native-image-", ".bin"); files.add(file);
            try (OutputStream output = new FilterOutputStream(Files.newOutputStream(file)) {
                @Override public void write(int value) throws IOException { write(new byte[] {(byte)value}, 0, 1); }
                @Override public void write(byte[] buffer, int offset, int length) throws IOException {
                    bytes += length; if (bytes > MAX_BINARY_BYTES) throw new IOException("native image response too large");
                    out.write(buffer, offset, length);
                }
            }) { parser.readBinaryValue(output); }
            return file;
        }
        @Override public void close() { for (Path file : files) { try { Files.deleteIfExists(file); } catch (IOException ignored) { } } }
    }
}
