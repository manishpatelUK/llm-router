package com.manishpateluk.llmrouter.error;

/**
 * Base type for every error this library raises. Unchecked — matches how the official
 * Anthropic/OpenAI SDKs themselves throw, and doesn't force every call site to declare or catch.
 * Carries a stable {@link ErrorCode} (§12.8) alongside the human-readable message so calling
 * code can branch on failure category without string-matching.
 */
public abstract class LlmRouterException extends RuntimeException {

    private final ErrorCode code;

    protected LlmRouterException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    protected LlmRouterException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
