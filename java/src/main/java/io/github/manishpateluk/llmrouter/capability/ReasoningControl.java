package io.github.manishpateluk.llmrouter.capability;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * How a model's API lets a caller set reasoning depth — {@code ModelEntry.reasoningControl}, see
 * {@code LIBRARY_SPEC.md} §7.1 and §7.4.
 */
public enum ReasoningControl {
    /** No caller-settable reasoning control; the thinking level only chooses the model. */
    NONE("none"),
    /** A named effort level, e.g. Anthropic {@code output_config.effort} or OpenAI {@code reasoning_effort}. */
    EFFORT("effort"),
    /** A thinking-token budget, e.g. Anthropic {@code thinking.budget_tokens} on older models. */
    BUDGET("budget");

    private final String wireValue;

    ReasoningControl(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static ReasoningControl fromWireValue(String value) {
        for (ReasoningControl control : values()) {
            if (control.wireValue.equals(value)) {
                return control;
            }
        }
        throw new IllegalArgumentException("Unknown reasoningControl: " + value);
    }

    @Override
    public String toString() {
        return wireValue;
    }
}
