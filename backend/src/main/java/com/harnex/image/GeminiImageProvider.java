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

/** Google's native generateContent image generation/editing API; reference images remain owned artifacts. */
@Component
public final class GeminiImageProvider implements ImageProvider {
    private final ImageHttpTransport transport;
    private final ArtifactStore assets;
    public GeminiImageProvider(ImageHttpTransport transport, ArtifactStore assets) { this.transport = transport; this.assets = assets; }
    @Override public String profile() { return NativeImageOptions.GEMINI; }
    @Override public Output generate(Input input, RunExecutionContext context, HttpExchangeState exchange, ResultSink sink) {
        String model = NativeImageOptions.geminiModel(input.service().model());
        RequestBody body = new RequestBody() {
            @Override public MediaType contentType() { return MediaType.get("application/json"); }
            @Override public void writeTo(BufferedSink sink) throws IOException {
                var bytes = sink.outputStream();
                try (JsonGenerator json = new JsonFactory().createGenerator(bytes).disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET)) {
                    json.writeStartObject(); json.writeArrayFieldStart("contents"); json.writeStartObject();
                    json.writeStringField("role", "user"); json.writeArrayFieldStart("parts");
                    json.writeStartObject(); json.writeStringField("text", input.prompt()); json.writeEndObject();
                    for (String source : input.spec().sourceArtifactIds()) {
                        context.checkActive();
                        json.writeStartObject(); json.writeObjectFieldStart("inlineData");
                        json.writeStringField("mimeType", assets.find(context.scopeId(), source).orElseThrow().mime());
                        json.writeFieldName("data"); ImageRequestBodies.base64(json, bytes, assets, context.scopeId(), source);
                        json.writeEndObject(); json.writeEndObject();
                    }
                    json.writeEndArray(); json.writeEndObject(); json.writeEndArray();
                    json.writeObjectFieldStart("generationConfig");
                    json.writeArrayFieldStart("responseModalities"); json.writeString("IMAGE"); json.writeEndArray();
                    var size = NativeImageOptions.geminiSize(input.spec().parameters().size());
                    if (size != null) {
                        json.writeObjectFieldStart("imageConfig");
                        json.writeStringField("aspectRatio", size.aspectRatio()); json.writeStringField("imageSize", size.resolution());
                        json.writeEndObject();
                    }
                    json.writeEndObject(); json.writeEndObject();
                }
            }
        };
        return transport.post(input, "/models/" + model + ":generateContent", body, context, exchange,
            stream -> NativeImageResults.gemini(stream, context, exchange, sink));
    }
}
