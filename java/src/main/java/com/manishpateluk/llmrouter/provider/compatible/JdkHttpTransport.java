package com.manishpateluk.llmrouter.provider.compatible;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Real {@link HttpTransport} implementation on the JDK's built-in {@link HttpClient} — no extra
 * dependency needed. {@link HttpClient#sendAsync} is genuinely non-blocking (async I/O, not a
 * thread wrapping a blocking call), so {@link #sendAsync} is overridden here rather than falling
 * back to the interface's thread-pool default.
 */
public final class JdkHttpTransport implements HttpTransport {

    private final HttpClient client;

    public JdkHttpTransport() {
        this(HttpClient.newHttpClient());
    }

    public JdkHttpTransport(HttpClient client) {
        this.client = client;
    }

    @Override
    public HttpResponseRecord send(HttpRequestRecord request) {
        try {
            HttpResponse<String> response = client.send(toJdkRequest(request), HttpResponse.BodyHandlers.ofString());
            return new HttpResponseRecord(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for HTTP response", e);
        }
    }

    @Override
    public CompletableFuture<HttpResponseRecord> sendAsync(HttpRequestRecord request) {
        return client.sendAsync(toJdkRequest(request), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> new HttpResponseRecord(response.statusCode(), response.body()));
    }

    private static HttpRequest toJdkRequest(HttpRequestRecord request) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(request.url()))
                .POST(HttpRequest.BodyPublishers.ofString(request.jsonBody()));
        for (Map.Entry<String, String> header : request.headers().entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
        return builder.build();
    }
}
