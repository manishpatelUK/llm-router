package com.manishpateluk.llmrouter.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** {@code Message.role} — see {@code LIBRARY_SPEC.md} §12.5. */
public enum Role {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool");

    private final String wireValue;

    Role(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static Role fromWireValue(String value) {
        for (Role role : values()) {
            if (role.wireValue.equals(value)) {
                return role;
            }
        }
        throw new IllegalArgumentException("Unknown message role: " + value);
    }

    @Override
    public String toString() {
        return wireValue;
    }
}
