package io.github.manishpateluk.llmrouter.model;

import java.util.List;
import java.util.Map;

import io.github.manishpateluk.llmrouter.config.ThinkingLevel;
import io.github.manishpateluk.llmrouter.provider.Provider;

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

    /**
     * Attachments left out for the model that answered, one per attachment, in request order —
     * see {@code LIBRARY_SPEC.md} §4. Empty when every attachment was sent. Whenever this is
     * non-empty, {@code droppedFeatures} also contains {@code "attachments"}.
     */
    @Builder.Default
    List<DroppedAttachment> droppedAttachments = List.of();

    /**
     * The thinking level the answering candidate was resolved at: the requested level, or a lower
     * one when {@code FallbackPolicy.FINISH} stepped down (§5.1.2).
     */
    ThinkingLevel thinkingLevel;

    /**
     * The reasoning control sent to the provider for the answering attempt (§7.4), or {@code null}
     * if none was sent (the model has none, or {@code RouterConfig.sendThinkingLevel} is off).
     */
    ReasoningSettings reasoning;

    /** Files the model produced, if any — see {@code LIBRARY_SPEC.md} §4. */
    @Builder.Default
    List<GeneratedFile> generatedFiles = List.of();

    /**
     * Sources the answer draws on, when the provider reports them (today: Perplexity) — see
     * {@code LIBRARY_SPEC.md} §3. In the provider's order: where {@code content} carries numbered
     * markers such as {@code [1]}, marker {@code [n]} refers to {@code citations.get(n - 1)}.
     * Empty when the provider reports none.
     */
    @Builder.Default
    List<Citation> citations = List.of();

    /**
     * The raw original output from the provider, for convenience — the actual SDK response
     * object (e.g. Anthropic's {@code Message}, OpenAI's {@code ChatCompletion}) or a parsed
     * JSON body for the raw-HTTP adapters, so a caller who knows the provider can cast to it.
     */
    Object original;
}
