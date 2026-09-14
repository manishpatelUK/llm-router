package com.manishpateluk.llmrouter.provider.compatible;

/** Raised when an OpenAI-compatible HTTP adapter's call returns a non-2xx status. */
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
