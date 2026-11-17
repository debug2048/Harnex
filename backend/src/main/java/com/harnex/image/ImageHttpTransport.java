package com.harnex.image;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnex.execution.RunExecutionContext;
import com.harnex.net.HttpClients;
import com.harnex.net.HttpExchangeState;
import com.harnex.net.OutboundProxyResolver;
import com.harnex.safety.SecretRedactor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** A single POST attempt. Local cancellation aborts the socket; upstream cancellation is not inferred from it. */
@Component
public final class ImageHttpTransport {
    private final OutboundProxyResolver proxies;
    private final ObjectMapper json = new ObjectMapper();
    public ImageHttpTransport(OutboundProxyResolver proxies) { this.proxies = proxies; }
    public <T> T post(ImageProvider.Input input, String endpoint, RequestBody body, RunExecutionContext context,
        HttpExchangeState exchange, ResponseReader<T> reader) {
        context.checkActive();
        URI base = URI.create(input.connection().baseUrl());
        OkHttpClient client = client(input, base, context);
        Request.Builder request = new Request.Builder().url(NativeImageOptions.endpoint(input.service().profile(), input.connection().baseUrl(), endpoint)).post(body)
            .tag(HttpExchangeState.class, exchange).header("Accept", "application/json");
        if (input.connection().apiKey() != null && !input.connection().apiKey().isBlank()) {
            if (NativeImageOptions.GEMINI.equals(input.service().profile())) request.header("x-goog-api-key", input.connection().apiKey());
            else request.header("Authorization", "Bearer " + input.connection().apiKey());
        }
        var call = client.newCall(request.build());
        try (var cancel = context.onCancel(call::cancel)) {
            context.checkActive();
            try (Response response = call.execute()) {
                if (!response.isSuccessful()) throw rejected(response, exchange, input);
                if (response.body() == null) throw new IOException("empty response body");
                return reader.read(response.body().byteStream());
            }
        } catch (ImageServiceException failure) { throw failure; }
        catch (CancellationException cancelled) { throw new ImageServiceException("image_cancelled", "Image operation cancelled locally", exchange.acceptance(), 0); }
        catch (Exception failure) {
            boolean cancelled = context.control().isRunCancelRequested();
            throw new ImageServiceException(cancelled ? "image_cancelled" : "image_transport_interrupted",
                cancelled ? "Image operation cancelled locally; the upstream result may still be pending" : "The image service response was interrupted",
                exchange.acceptance(), exchange.retryAfterMillis());
        }
    }
    /** Legacy signed result URLs are downloaded without service credentials, redirects, or access to private hosts. */
    public void download(ImageProvider.Input input, String url, RunExecutionContext context, OutputStream output) throws IOException {
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getFragment() != null || uri.getPort() != -1 && uri.getPort() != 443) throw new IOException("unsupported image result URL");
            List<InetAddress> addresses = List.of(InetAddress.getAllByName(uri.getHost()));
            if (addresses.isEmpty() || addresses.stream().anyMatch(ImageHttpTransport::privateAddress)) throw new IOException("private image result host");
            // DNS is pinned and the proxy is disabled so the validated addresses are the actual destination.
            OkHttpClient client = client(input, uri, context).newBuilder().proxy(Proxy.NO_PROXY)
                .dns(host -> { if (!host.equalsIgnoreCase(uri.getHost())) throw new UnknownHostException(); return addresses; }).build();
            var call = client.newCall(new Request.Builder().url(url).get().build());
            try (var cancel = context.onCancel(call::cancel)) {
                context.checkActive();
                try (var response = call.execute()) {
                    if (!response.isSuccessful() || response.body() == null) throw new IOException("image result download failed");
                    response.body().byteStream().transferTo(output);
                }
            }
        } catch (IOException failure) { throw failure; }
        catch (Exception failure) { throw new IOException("image result unavailable", failure); }
    }
    private OkHttpClient client(ImageProvider.Input input, URI uri, RunExecutionContext context) {
        long remaining = Duration.between(Instant.now(), context.deadline()).toMillis();
        if (remaining <= 0) throw new CancellationException("run deadline exceeded");
        int port = uri.getPort() > 0 ? uri.getPort() : "https".equals(uri.getScheme()) ? 443 : 80;
        return HttpClients.modelBuilder(proxies.resolveGlobal(uri.getHost(), port))
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .callTimeout(Duration.ofMillis(Math.max(1, Math.min(input.service().timeoutMillis(), remaining)))).build();
    }
    private ImageServiceException rejected(Response response, HttpExchangeState exchange, ImageProvider.Input input) throws IOException {
        String upstreamCode = "", upstreamParam = "", upstreamMessage = "";
        if (response.body() != null) {
            byte[] bytes = response.body().byteStream().readNBytes(64 * 1024);
            try {
                var root = json.readTree(bytes); var error = root.path("error");
                upstreamCode = safeIdentifier(error.path("code").asText(root.path("code").asText("")), input);
                upstreamParam = safeIdentifier(error.path("param").asText(""), input);
                String detail = error.isTextual() ? error.asText() : error.path("message").asText(root.path("message").asText(""));
                if (detail.isBlank()) detail = root.path("detail").asText("");
                upstreamMessage = safeMessage(detail, input, 768);
            } catch (Exception ignored) { /* Non-JSON or incomplete errors retain the bounded status explanation. */ }
        }
        String code, message;
        if (response.code() == 404 || List.of("model_not_found", "model_not_available", "invalid_model").contains(upstreamCode)) {
            code = "image_model_unavailable"; message = "The configured image model or image endpoint does not exist or is unavailable to this connection";
        } else if (response.code() == 401 || response.code() == 403) {
            code = "image_access_denied"; message = "The image service rejected the credentials or model access";
        } else if (response.code() == 429) {
            code = "image_rate_limited"; message = "The image service is rate limited; the input and image parameters are preserved";
        } else if (response.code() >= 500) {
            code = "image_service_unavailable"; message = "The image service is temporarily unavailable";
        } else { code = "image_request_rejected"; message = "The image service rejected the request (HTTP " + response.code() + ")"; }
        if (!upstreamCode.isEmpty()) message += "; upstream code=" + upstreamCode;
        if (!upstreamParam.isEmpty()) message += "; parameter=" + upstreamParam;
        if (!upstreamMessage.isEmpty()) message += ". " + upstreamMessage;
        return new ImageServiceException(code, message, exchange.acceptance(), exchange.retryAfterMillis());
    }
    private static String safeIdentifier(String value, ImageProvider.Input input) {
        String safe = safeMessage(value, input, 96);
        return safe.matches("[A-Za-z0-9_.\\[\\]-]{1,96}") ? safe : "";
    }
    private static String safeMessage(String value, ImageProvider.Input input, int limit) {
        String key = input.connection().apiKey();
        if (key != null && !key.isBlank()) value = value.replace(key, "[credential omitted]");
        if (input.prompt() != null && !input.prompt().isBlank()) value = value.replace(input.prompt(), "[prompt omitted]");
        value = SecretRedactor.redact(value)
            .replaceAll("(?i)data:[^\\s\"'<>]{1,65536}", "[image data omitted]")
            .replaceAll("[A-Za-z0-9+/=_-]{128,65536}", "[data omitted]")
            .replaceAll("[\\p{Cntrl}&&[^\\n\\r\\t]]", "").replaceAll("\\s+", " ").strip();
        return value.length() > limit ? value.substring(0, limit) + "…" : value;
    }
    private static boolean privateAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        return address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()
            || address.isMulticastAddress() || bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc
            || bytes.length == 4 && ((bytes[0] & 255) == 0 || (bytes[0] & 255) == 100 && (bytes[1] & 0xc0) == 64
                || (bytes[0] & 255) == 169 && (bytes[1] & 255) == 254 || (bytes[0] & 255) >= 224);
    }
    @FunctionalInterface public interface ResponseReader<T> { T read(InputStream stream) throws IOException; }
}
