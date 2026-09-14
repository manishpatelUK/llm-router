package com.manishpateluk.llmrouter.provider;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Canonical provider id — the one closed enum in the library spec (unlike {@code model}, which
 * is deliberately open). See {@code LIBRARY_SPEC.md} §12.1.
 *
 * <p>Used everywhere the spec calls for a provider identifier: {@code RouteEntry.provider},
 * {@code ModelEntry.provider}, {@code ProviderAdapter.id}, {@code Response.providerUsed},
 * {@code AttemptRecord.provider}, and as the key into the §6.1 environment-variable table.
 */
public enum Provider {
    ANTHROPIC("anthropic"),
    OPENAI("openai"),
    PERPLEXITY("perplexity"),
    NVIDIA("nvidia"),
    HUGGINGFACE("huggingface"),
    OPENROUTER("openrouter");

    private final String id;

    Provider(String id) {
        this.id = id;
    }

    /** The exact, case-sensitive canonical id string — see {@code LIBRARY_SPEC.md} §12.1. */
    @JsonValue
    public String id() {
        return id;
    }

    /**
     * Resolves a canonical id string back to its {@link Provider}.
     *
     * @throws IllegalArgumentException if {@code id} isn't one of the canonical §12.1 values
     */
    @JsonCreator
    public static Provider fromId(String id) {
        for (Provider provider : values()) {
            if (provider.id.equals(id)) {
                return provider;
            }
        }
        throw new IllegalArgumentException("Unknown provider id: " + id);
    }

    @Override
    public String toString() {
        return id;
    }
}
