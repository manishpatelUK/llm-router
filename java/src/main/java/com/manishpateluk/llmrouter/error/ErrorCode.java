package com.manishpateluk.llmrouter.error;

/** Stable, machine-readable failure category — see {@code LIBRARY_SPEC.md} §12.8. */
public enum ErrorCode {
    ROUTER_EXHAUSTED,
    NO_PROVIDERS_CONFIGURED,
    INVALID_CONFIG,
}
