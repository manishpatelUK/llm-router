package com.manishpateluk.llmrouter.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * How the router should handle a requested {@code responseSchema} when the chosen model doesn't
 * natively support structured output — see {@code LIBRARY_SPEC.md} §4 and §12.4.
 */
public enum StructuredOutputStrategy {
    NATIVE("native"),
    PROMPT_FALLBACK("promptFallback"),
    AUTO("auto");

    private final String wireValue;

    StructuredOutputStrategy(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static StructuredOutputStrategy fromWireValue(String value) {
        for (StructuredOutputStrategy strategy : values()) {
            if (strategy.wireValue.equals(value)) {
                return strategy;
            }
        }
        throw new IllegalArgumentException("Unknown structuredOutputStrategy: " + value);
    }

    @Override
    public String toString() {
        return wireValue;
    }
}
