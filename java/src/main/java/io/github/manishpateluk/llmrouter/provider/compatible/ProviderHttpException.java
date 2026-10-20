package io.github.manishpateluk.llmrouter.provider.compatible;

import java.time.Duration;

/** Raised when a raw-HTTP adapter's call (OpenAI-compatible or Perplexity) returns a non-2xx status. */
public final class ProviderHttpException extends RuntimeException {

    private final int statusCode;
    private final Duration retryAfter;

    public ProviderHttpException(int statusCode, String responseBody) {
        this(statusCode, responseBody, null);
    }

    /** @param retryAfter the wait the provider asked for before retrying, or {@code null} */
    public ProviderHttpException(int statusCode, String responseBody, Duration retryAfter) {
        super("HTTP " + statusCode + ": " + responseBody);
        this.statusCode = statusCode;
        this.retryAfter = retryAfter;
    }

    public int statusCode() {
        return statusCode;
    }

    /** The wait the provider asked for in {@code Retry-After}, or {@code null} if it gave none. */
    public Duration retryAfter() {
        return retryAfter;
    }
}
