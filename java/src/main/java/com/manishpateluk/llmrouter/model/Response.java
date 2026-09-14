package com.manishpateluk.llmrouter.model;

import java.util.List;
import java.util.Map;

import com.manishpateluk.llmrouter.provider.Provider;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * The unified response shape returned by every call variant on the router, regardless of which
 * provider actually served the request — see {@code LIBRARY_SPEC.md} §3.
 */
@Value
@Builder(toBuilder = true)
@Jacksonized
public class Response {

    /** Primary text output. */
    String content;

    /** Present if a {@code responseSchema} was honored, natively or via prompt fallback. */
    Map<String, Object> structuredOutput;

    @Builder.Default
    List<ToolCall> toolCalls = List.of();

    Provider providerUsed;
    String modelUsed;
    Usage usage;

    /** Values per {@code LIBRARY_SPEC.md} §12.7, e.g. {@code ["responseSchema", "tools"]}. */
    @Builder.Default
    List<String> droppedFeatures = List.of();

    /** One entry per candidate tried before success; empty if the first candidate succeeded. */
    @Builder.Default
    List<AttemptRecord> attempts = List.of();

    /** Files the model produced, if any — see {@code LIBRARY_SPEC.md} §4. */
    @Builder.Default
    List<GeneratedFile> generatedFiles = List.of();

    /**
     * The raw original output from the provider, for convenience — the actual SDK response
     * object (e.g. Anthropic's {@code Message}, OpenAI's {@code ChatCompletion}) or a parsed
     * JSON body for the raw-HTTP adapters, so a caller who knows the provider can cast to it.
     */
    Object original;
}
