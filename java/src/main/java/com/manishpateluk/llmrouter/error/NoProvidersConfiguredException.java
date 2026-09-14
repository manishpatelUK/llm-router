package com.manishpateluk.llmrouter.error;

/**
 * Raised on first use if no provider credentials were detected anywhere — see
 * {@code LIBRARY_SPEC.md} §5.2, §10. Fails fast rather than silently no-op-ing.
 */
public final class NoProvidersConfiguredException extends LlmRouterException {

    public NoProvidersConfiguredException(String message) {
        super(ErrorCode.NO_PROVIDERS_CONFIGURED, message);
    }
}
