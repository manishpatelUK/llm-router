package io.github.manishpateluk.llmrouter.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A request feature that {@code RouterConfig.requiredFeatures} can mark as must-not-drop — see
 * {@code LIBRARY_SPEC.md} §5.4 and §12.9. Each wire value is the same canonical string the
 * negotiator records in {@code droppedFeatures} (§12.7) when it can't honor that feature.
 */
public enum Feature {
    TOOLS("tools"),
    RESPONSE_SCHEMA("responseSchema"),
    ATTACHMENTS("attachments");

    private final String wireValue;

    Feature(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static Feature fromWireValue(String value) {
        for (Feature feature : values()) {
            if (feature.wireValue.equals(value)) {
                return feature;
            }
        }
        throw new IllegalArgumentException("Unknown feature: " + value);
    }

    @Override
    public String toString() {
        return wireValue;
    }
}
