package com.manishpateluk.llmrouter.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Requested reasoning-depth tier for provider-only route resolution — see
 * {@code LIBRARY_SPEC.md} §7.2 for the full selection heuristic and §12.3 for the canonical
 * wire values.
 */
public enum ThinkingLevel {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    MAX("max");

    private final String wireValue;

    ThinkingLevel(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static ThinkingLevel fromWireValue(String value) {
        for (ThinkingLevel level : values()) {
            if (level.wireValue.equals(value)) {
                return level;
            }
        }
        throw new IllegalArgumentException("Unknown thinkingLevel: " + value);
    }

    @Override
    public String toString() {
        return wireValue;
    }
}
