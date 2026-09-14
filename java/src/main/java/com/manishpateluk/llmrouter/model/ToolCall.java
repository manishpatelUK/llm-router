package com.manishpateluk.llmrouter.model;

import java.util.Map;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/** A tool invocation the model requested — see {@code LIBRARY_SPEC.md} §3 (`Response.toolCalls`). */
@Value
@Builder
@Jacksonized
public class ToolCall {

    /** Provider-assigned id for this call, if the provider assigns one. */
    String id;

    String name;

    /** Parsed call arguments. */
    Map<String, Object> arguments;
}
