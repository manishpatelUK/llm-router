package com.manishpateluk.llmrouter.error;

/**
 * Raised immediately at call time for a malformed {@code RouterConfig} — see
 * {@code LIBRARY_SPEC.md} §10. Not buried inside routing logic.
 */
public final class InvalidConfigException extends LlmRouterException {

    public InvalidConfigException(String message) {
        super(ErrorCode.INVALID_CONFIG, message);
    }
}
