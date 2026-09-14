package com.manishpateluk.llmrouter.provider.compatible;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.manishpateluk.llmrouter.provider.ProviderAdapter;

/**
 * A minimal HTTP transport seam — lets {@link OpenAiCompatibleHttpAdapter} be tested without any
 * real network I/O, by mocking this interface instead of {@code java.net.http.HttpClient}
 * directly (which has no interface of its own to mock against).
 */
public interface HttpTransport {

    HttpResponseRecord send(HttpRequestRecord request);

    /** Default: run {@link #send} on a fresh virtual thread. Override for real non-blocking I/O. */
    default CompletableFuture<HttpResponseRecord> sendAsync(HttpRequestRecord request) {
        return CompletableFuture.supplyAsync(() -> send(request), ProviderAdapter.DEFAULT_ASYNC_EXECUTOR);
    }

    record HttpRequestRecord(String url, Map<String, String> headers, String jsonBody) {
    }

    record HttpResponseRecord(int statusCode, String body) {
    }
}
