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

/** Responses' native image tool is isolated from the ordinary conversation tool protocol. */
@Component
public final class ResponsesImageProvider implements ImageProvider {
    private final ImageHttpTransport transport;
    private final ArtifactStore assets;
    public ResponsesImageProvider(ImageHttpTransport transport, ArtifactStore assets) { this.transport = transport; this.assets = assets; }
    @Override public String profile() { return "openai-responses-image"; }
    @Override public Output generate(Input input, RunExecutionContext context, HttpExchangeState exchange, ResultSink sink) {
        RequestBody body = new RequestBody() {
            @Override public MediaType contentType() { return MediaType.get("application/json"); }
            @Override public void writeTo(BufferedSink sink) throws IOException {
                var bytes = sink.outputStream();
                try (JsonGenerator json = new JsonFactory().createGenerator(bytes).disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET)) {
                    json.writeStartObject(); json.writeStringField("model", input.service().mainModel());
                    json.writeBooleanField("store", false); json.writeBooleanField("stream", false); json.writeNumberField("max_tool_calls", 1);
                    json.writeObjectFieldStart("tool_choice"); json.writeStringField("type", "image_generation"); json.writeEndObject();
                    json.writeArrayFieldStart("input"); json.writeStartObject(); json.writeStringField("role", "user");
                    json.writeArrayFieldStart("content"); json.writeStartObject(); json.writeStringField("type", "input_text");
                    json.writeStringField("text", input.prompt()); json.writeEndObject();
                    for (String source : input.spec().sourceArtifactIds()) {
                        json.writeStartObject(); json.writeStringField("type", "input_image"); json.writeFieldName("image_url");
                        ImageRequestBodies.dataUrl(json, bytes, assets, context.scopeId(), source); json.writeEndObject();
                    }
                    json.writeEndArray(); json.writeEndObject(); json.writeEndArray();
                    json.writeArrayFieldStart("tools"); json.writeStartObject(); json.writeStringField("type", "image_generation");
                    json.writeStringField("model", input.service().model());
                    json.writeStringField("action", input.spec().sourceArtifactIds().isEmpty() ? "generate" : "edit");
                    ImageRequestBodies.parameters(json, input.spec().parameters());
                    if (input.spec().maskArtifactId() != null) {
                        json.writeObjectFieldStart("input_image_mask"); json.writeFieldName("image_url");
                        ImageRequestBodies.dataUrl(json, bytes, assets, context.scopeId(), input.spec().maskArtifactId()); json.writeEndObject();
                    }
                    json.writeEndObject(); json.writeEndArray(); json.writeEndObject();
                }
            }
        };
        return transport.post(input, "/responses", body, context, exchange,
            stream -> ImageJsonResults.read(stream, true, input, context, exchange, sink, transport));
    }
}
