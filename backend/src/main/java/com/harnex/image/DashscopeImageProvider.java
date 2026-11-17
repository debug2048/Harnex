package com.harnex.image;

import java.io.IOException;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.harnex.artifact.ArtifactStore;
import com.harnex.execution.RunExecutionContext;
import com.harnex.net.HttpExchangeState;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.BufferedSink;

/** Qwen's synchronous native DashScope API. Generates and edits on the same JSON endpoint. */
@Component
public final class DashscopeImageProvider implements ImageProvider {
    private final ImageHttpTransport transport;
    private final ArtifactStore assets;
    public DashscopeImageProvider(ImageHttpTransport transport, ArtifactStore assets) { this.transport = transport; this.assets = assets; }
    @Override public String profile() { return NativeImageOptions.QWEN; }
    @Override public Output generate(Input input, RunExecutionContext context, HttpExchangeState exchange, ResultSink sink) {
        RequestBody body = new RequestBody() {
            @Override public MediaType contentType() { return MediaType.get("application/json"); }
            @Override public void writeTo(BufferedSink sink) throws IOException {
                var bytes = sink.outputStream();
                try (JsonGenerator json = new JsonFactory().createGenerator(bytes).disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET)) {
                    json.writeStartObject(); json.writeStringField("model", input.service().model());
                    json.writeObjectFieldStart("input"); json.writeArrayFieldStart("messages"); json.writeStartObject();
                    json.writeStringField("role", "user"); json.writeArrayFieldStart("content");
                    for (String source : input.spec().sourceArtifactIds()) {
                        context.checkActive(); json.writeStartObject(); json.writeFieldName("image");
                        ImageRequestBodies.dataUrl(json, bytes, assets, context.scopeId(), source); json.writeEndObject();
                    }
                    json.writeStartObject(); json.writeStringField("text", input.prompt()); json.writeEndObject();
                    json.writeEndArray(); json.writeEndObject(); json.writeEndArray(); json.writeEndObject();
                    json.writeObjectFieldStart("parameters"); json.writeNumberField("n", input.spec().parameters().count());
                    String size = input.spec().parameters().size();
                    if (!"auto".equals(size)) json.writeStringField("size", size.replace('x', '*'));
                    json.writeEndObject(); json.writeEndObject();
                }
            }
        };
        return transport.post(input, NativeImageOptions.QWEN_PATH, body, context, exchange,
            stream -> NativeImageResults.dashscope(stream, input, context, exchange, sink, transport));
    }
}
