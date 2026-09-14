package com.manishpateluk.llmrouter.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** {@code AttemptRecord.outcome} — see {@code LIBRARY_SPEC.md} §12.6. */
public enum AttemptOutcome {
    SUCCESS("success"),
    FAILED("failed"),
    SKIPPED("skipped");

    private final String wireValue;

    AttemptOutcome(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static AttemptOutcome fromWireValue(String value) {
        for (AttemptOutcome outcome : values()) {
            if (outcome.wireValue.equals(value)) {
                return outcome;
            }
        }
        throw new IllegalArgumentException("Unknown attempt outcome: " + value);
    }

    @Override
    public String toString() {
        return wireValue;
    }
}
