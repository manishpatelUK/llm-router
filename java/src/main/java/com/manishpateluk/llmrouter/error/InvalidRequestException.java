package com.manishpateluk.llmrouter.error;

/**
 * Raised immediately at call time, before any provider is contacted, for a {@code Request} that
 * every provider would refuse — e.g. a malformed tool definition. See {@code LIBRARY_SPEC.md} §10.
 */
public final class InvalidRequestException extends LlmRouterException {

    public InvalidRequestException(String message) {
        super(ErrorCode.INVALID_REQUEST, message);
    }
}
