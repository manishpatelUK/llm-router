package com.manishpateluk.llmrouter.model;

import java.util.List;
import java.util.Map;

import com.manishpateluk.llmrouter.config.RouterConfig;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * The unified request shape every call variant on the router ultimately builds — see
 * {@code LIBRARY_SPEC.md} §3.
 *
 * <p>{@code history}, {@code tools}, and {@code attachments} default to an empty list rather
 * than {@code null} so callers never need a null-check before iterating them; the remaining
 * optional fields default to {@code null} since they have no natural "empty" value.
 */
@Value
@Builder(toBuilder = true)
@Jacksonized
public class Request {

    /** Required — the latest user message. */
    String prompt;

    @Builder.Default
    List<Message> history = List.of();

    String systemInstructions;

    /** JSON-Schema-like structured-output schema, as a raw parsed JSON object. */
    Map<String, Object> responseSchema;

    @Builder.Default
    List<ToolDefinition> tools = List.of();

    @Builder.Default
    List<Attachment> attachments = List.of();

    /** Falls back to the router's default config when {@code null}. */
    RouterConfig config;

    /**
     * Resolved from {@code RouterConfig.temperature} during capability negotiation (§4) —
     * {@code null} if not requested, or dropped because the candidate model doesn't support it.
     * Not intended to be set directly by callers; set {@code RouterConfig.temperature} instead.
     */
    Double temperature;

    /**
     * Resolved from {@code RouterConfig.topP} during capability negotiation (§4) — {@code null}
     * if not requested, or dropped because the candidate model doesn't support it. Not intended
     * to be set directly by callers; set {@code RouterConfig.topP} instead.
     */
    Double topP;
}
