package com.manishpateluk.llmrouter.model;

import java.util.Map;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * A tool/function the model may call — see {@code LIBRARY_SPEC.md} §3. The router never
 * executes tool calls itself; it only requests them and returns them to the caller.
 */
@Value
@Builder
@Jacksonized
public class ToolDefinition {

    String name;
    String description;

    /** JSON-Schema-like description of the tool's arguments, as a raw parsed JSON object. */
    Map<String, Object> parameters;
}
