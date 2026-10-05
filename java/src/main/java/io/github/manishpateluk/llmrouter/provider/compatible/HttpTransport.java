package io.github.manishpateluk.llmrouter.provider.compatible;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import io.github.manishpateluk.llmrouter.provider.ProviderAdapter;

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

    /**
     * Sends {@code request} and delivers a successful (2xx) response body to {@code onLine} one
     * line at a time, as it arrives — for server-sent-event streams. The returned record carries
     * the status code; its body is the full error body for a non-2xx status (in which case no
     * lines are delivered) and empty otherwise.
     *
     * <p>Default: {@link #send} the request, then replay the buffered body line by line. That
     * keeps any transport working with streaming calls, just without incremental delivery;
     * override it to stream for real.
     */
    default HttpResponseRecord sendStreaming(HttpRequestRecord request, Consumer<String> onLine) {
        HttpResponseRecord response = send(request);
        if (response.statusCode() >= 300) {
            return response;
        }
        response.body().lines().forEach(onLine);
        return new HttpResponseRecord(response.statusCode(), "");
    }

    record HttpRequestRecord(String url, Map<String, String> headers, String jsonBody) {
    }

    record HttpResponseRecord(int statusCode, String body) {
    }
}
