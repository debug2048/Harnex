package com.harnex.image;

import java.io.IOException;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.artifact.ArtifactStore;
import com.harnex.execution.RunExecutionContext;
import com.harnex.net.HttpExchangeState;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;

/** Official Images protocol. Image models are configured independently from text providers. */
@Component
public final class OpenAiImagesProvider implements ImageProvider {
    private final ImageHttpTransport transport;
    private final ArtifactStore assets;
    private final ObjectMapper json = new ObjectMapper();
    public OpenAiImagesProvider(ImageHttpTransport transport, ArtifactStore assets) { this.transport = transport; this.assets = assets; }
    @Override public String profile() { return "openai-images"; }
    @Override public Output generate(Input input, RunExecutionContext context, HttpExchangeState exchange, ResultSink sink) {
        var parameters = input.spec().parameters();
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("model", input.service().model()); fields.put("prompt", input.prompt()); fields.put("n", parameters.count());
        fields.put("size", parameters.size()); fields.put("quality", parameters.quality()); fields.put("output_format", parameters.format());
        fields.put("background", parameters.background());
        RequestBody body;
        boolean edit = !input.spec().sourceArtifactIds().isEmpty();
        if (edit) {
            MultipartBody.Builder form = new MultipartBody.Builder().setType(MultipartBody.FORM);
            fields.forEach((key, value) -> form.addFormDataPart(key, String.valueOf(value)));
            for (String id : input.spec().sourceArtifactIds()) form.addFormDataPart("image[]", "source-" + id,
                ImageRequestBodies.asset(assets, context.scopeId(), id));
            if (input.spec().maskArtifactId() != null) form.addFormDataPart("mask", "mask.png",
                ImageRequestBodies.asset(assets, context.scopeId(), input.spec().maskArtifactId()));
            body = form.build();
        } else {
            try { body = RequestBody.create(json.writeValueAsBytes(fields), MediaType.get("application/json")); }
            catch (IOException failure) { throw new ImageServiceException("image_request_invalid", "Could not build the image request"); }
        }
        return transport.post(input, edit ? "/images/edits" : "/images/generations", body, context, exchange,
            stream -> ImageJsonResults.read(stream, false, input, context, exchange, sink, transport));
    }
}
