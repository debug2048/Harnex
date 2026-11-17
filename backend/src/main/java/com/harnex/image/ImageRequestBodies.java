package com.harnex.image;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Base64;
import com.fasterxml.jackson.core.JsonGenerator;
import com.harnex.artifact.ArtifactStore;
import com.harnex.execution.ImageSpec;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.BufferedSink;

final class ImageRequestBodies {
    static RequestBody asset(ArtifactStore assets, String scope, String id) {
        return new RequestBody() {
            @Override public MediaType contentType() { return MediaType.get(assets.find(scope, id).orElseThrow().mime()); }
            @Override public long contentLength() { return assets.find(scope, id).orElseThrow().bytes(); }
            @Override public void writeTo(BufferedSink sink) throws IOException {
                try (var content = assets.open(scope, id)) { content.stream().transferTo(sink.outputStream()); }
            }
        };
    }
    static void parameters(JsonGenerator json, ImageSpec.Parameters parameters) throws IOException {
        json.writeStringField("size", parameters.size()); json.writeStringField("quality", parameters.quality());
        json.writeStringField("output_format", parameters.format()); json.writeStringField("background", parameters.background());
    }
    static void dataUrl(JsonGenerator json, OutputStream bytes, ArtifactStore assets, String scope, String id) throws IOException {
        binary(json, bytes, assets, scope, id, true);
    }
    static void base64(JsonGenerator json, OutputStream bytes, ArtifactStore assets, String scope, String id) throws IOException {
        binary(json, bytes, assets, scope, id, false);
    }
    private static void binary(JsonGenerator json, OutputStream bytes, ArtifactStore assets, String scope, String id, boolean dataUrl) throws IOException {
        try (var source = assets.open(scope, id)) {
            // writeRawValue accounts for the JSON value separator; binary is encoded incrementally after it.
            json.writeRawValue(dataUrl ? "\"data:" + source.metadata().mime() + ";base64," : "\""); json.flush();
            OutputStream encoded = Base64.getEncoder().wrap(new FilterOutputStream(bytes) {
                @Override public void write(byte[] buffer, int offset, int length) throws IOException { out.write(buffer, offset, length); }
                @Override public void close() throws IOException { flush(); }
            });
            source.stream().transferTo(encoded); encoded.close(); bytes.write('"');
        }
    }
}
