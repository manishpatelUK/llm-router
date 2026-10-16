package io.github.manishpateluk.llmrouter.provider.compatible;

/** Raised when a raw-HTTP adapter's call (OpenAI-compatible or Perplexity) returns a non-2xx status. */
public final class ProviderHttpException extends RuntimeException {

    private final int statusCode;

    public ProviderHttpException(int statusCode, String responseBody) {
        super("HTTP " + statusCode + ": " + responseBody);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
