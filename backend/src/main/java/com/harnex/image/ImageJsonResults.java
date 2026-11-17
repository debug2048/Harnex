package com.harnex.image;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.harnex.execution.RunExecutionContext;
import com.harnex.net.HttpExchangeState;

/** Streaming JSON decoder: base64 is decoded straight into a bounded durable candidate file. */
final class ImageJsonResults {
    private static final JsonFactory JSON = new JsonFactory();
    static ImageProvider.Output read(InputStream stream, boolean responses, ImageProvider.Input input,
        RunExecutionContext context, HttpExchangeState exchange, ImageProvider.ResultSink sink, ImageHttpTransport transport) throws IOException {
        String handle = null, status = null;
        Map<String, Long> usage = new LinkedHashMap<>();
        int count = 0;
        boolean failed = false;
        try (JsonParser parser = JSON.createParser(stream)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw new IOException("invalid image response");
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() == null) throw new IOException("incomplete image response");
                String key = parser.currentName(); parser.nextToken();
                if ("id".equals(key)) handle = bounded(parser, 256);
                else if ("status".equals(key)) status = bounded(parser, 64);
                else if ("error".equals(key) && parser.currentToken() != JsonToken.VALUE_NULL) { failed = true; parser.skipChildren(); }
                else if ("usage".equals(key)) readUsage(parser, "", usage, 0);
                else if ((responses ? "output" : "data").equals(key) && parser.currentToken() == JsonToken.START_ARRAY) {
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        if (parser.currentToken() != JsonToken.START_OBJECT) throw new IOException("invalid image output");
                        String type = null, itemStatus = null;
                        boolean received = false;
                        while (parser.nextToken() != JsonToken.END_OBJECT) {
                            if (parser.currentToken() == null) throw new IOException("incomplete image candidate");
                            String field = parser.currentName(); parser.nextToken();
                            if ("type".equals(field)) type = bounded(parser, 64);
                            else if ("status".equals(field)) itemStatus = bounded(parser, 64);
                            else if ((responses ? "result" : "b64_json").equals(field) && parser.currentToken() == JsonToken.VALUE_STRING) {
                                if (received) throw new IOException("duplicate result in image candidate");
                                sink.receive(count++, output -> parser.readBinaryValue(output)); received = true; exchange.outputReceived();
                            } else if (!responses && "url".equals(field) && parser.currentToken() == JsonToken.VALUE_STRING) {
                                String url = bounded(parser, 8192);
                                if (!received) { sink.receive(count++, output -> transport.download(input, url, context, output)); received = true; exchange.outputReceived(); }
                            } else parser.skipChildren();
                        }
                        if (responses && received && (!"image_generation_call".equals(type) || itemStatus != null && !"completed".equals(itemStatus))) failed = true;
                        if (responses && "image_generation_call".equals(type) && !received) failed = true;
                    }
                } else parser.skipChildren();
            }
            if (parser.nextToken() != null) throw new IOException("trailing response data");
        }
        return new ImageProvider.Output(handle, usage, !failed && (!responses || "completed".equals(status)));
    }
    private static void readUsage(JsonParser parser, String prefix, Map<String, Long> usage, int depth) throws IOException {
        if (parser.currentToken() != JsonToken.START_OBJECT || depth > 2) { parser.skipChildren(); return; }
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() == null) throw new IOException("incomplete image usage");
            String key = parser.currentName(); parser.nextToken();
            if (!key.matches("(?:input|output|total|text|image|cached|reasoning)_tokens(?:_details)?")) { parser.skipChildren(); continue; }
            String name = prefix + key;
            if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT) usage.put(name, Math.max(0, parser.getLongValue()));
            else readUsage(parser, name + ".", usage, depth + 1);
        }
    }
    private static String bounded(JsonParser parser, int limit) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_STRING) { parser.skipChildren(); return null; }
        String text = parser.getValueAsString(); if (text.length() > limit) throw new IOException("image metadata too large"); return text;
    }
}
