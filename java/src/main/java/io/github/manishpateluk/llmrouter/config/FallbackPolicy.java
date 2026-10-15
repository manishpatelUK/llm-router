package io.github.manishpateluk.llmrouter.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * What the router does once every candidate at the requested {@link ThinkingLevel} has failed or
 * been skipped — see {@code LIBRARY_SPEC.md} §5.1.2 and §12.10 for the canonical wire values.
 */
public enum FallbackPolicy {
    /** Throw {@code RouterExhaustedException} — the behaviour before this policy existed. */
    SAME_DEPTH("sameDepth"),
    /**
     * Try provider-only entries and the default route again one thinking level lower, down to
     * {@code LOW}, before throwing. Never steps up.
     */
    FINISH("finish");

    private final String wireValue;

    FallbackPolicy(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static FallbackPolicy fromWireValue(String value) {
        for (FallbackPolicy policy : values()) {
            if (policy.wireValue.equals(value)) {
                return policy;
            }
        }
        throw new IllegalArgumentException("Unknown fallback policy: " + value);
    }

    @Override
    public String toString() {
        return wireValue;
    }
}
