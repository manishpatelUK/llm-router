package io.github.manishpateluk.llmrouter.provider.compatible;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import io.github.manishpateluk.llmrouter.provider.ProviderAdapter;
import io.github.manishpateluk.llmrouter.provider.TransientFailures;

/**
 * A minimal HTTP transport seam — lets {@link OpenAiCompatibleHttpAdapter} and the Perplexity
 * adapter be tested without any real network I/O, by mocking this interface instead of
 * {@code java.net.http.HttpClient} directly (which has no interface of its own to mock against).
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

    /**
     * @param headers response headers by name; only read for an error status (e.g.
     *        {@code Retry-After}), so a transport may leave it empty
     */
    record HttpResponseRecord(int statusCode, String body, Map<String, List<String>> headers) {

        public HttpResponseRecord {
            headers = headers == null ? Map.of() : headers;
        }

        public HttpResponseRecord(int statusCode, String body) {
            this(statusCode, body, Map.of());
        }

        /** A header's values, matching its name case-insensitively; empty if absent. */
        public List<String> header(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
            return List.of();
        }

        /** The wait the provider asked for in {@code Retry-After} (or {@code retry-after-ms}), or {@code null}. */
        public Duration retryAfter() {
            return TransientFailures.retryAfter(this::header);
        }
    }
}
