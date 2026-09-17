package com.manishpateluk.llmrouter.config;

import java.util.List;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * Reusable, long-lived routing configuration — see {@code LIBRARY_SPEC.md} §5. Applications
 * typically build one (or a small number of named ones) at startup and reuse it; it is
 * deliberately not rebuilt per request.
 *
 * <p>{@code thinkingLevel}, {@code costOptimized}, and {@code structuredOutputStrategy} always
 * carry their spec-defined default when not explicitly set, so downstream routing code never
 * needs to null-check them. {@code route} is the one field that stays {@code null} by default —
 * unlike the others, "no route given" resolves dynamically from available providers (§5.2)
 * rather than to a fixed value, so a {@code RouterConfig.builder().build()} with no other
 * fields set behaves identically to no config being passed at all.
 */
@Value
@Builder
@Jacksonized
public class RouterConfig {

    /** Ordered fallback preference; {@code null} means "use the computed default" (§5.2). */
    List<RouteEntry> route;

    @Builder.Default
    ThinkingLevel thinkingLevel = ThinkingLevel.MEDIUM;

    @Builder.Default
    boolean costOptimized = false;

    @Builder.Default
    StructuredOutputStrategy structuredOutputStrategy = StructuredOutputStrategy.AUTO;

    /**
     * Optional sampling temperature; {@code null} means "use the provider's own default". Sent
     * to the model only if the resolved candidate's {@code ModelEntry.supportsTemperature} is
     * {@code true} — otherwise omitted for that attempt and recorded in {@code droppedFeatures}
     * (§4).
     */
    Double temperature;

    /**
     * Optional nucleus-sampling (top-p) parameter; {@code null} means "use the provider's own
     * default". Sent to the model only if the resolved candidate's {@code ModelEntry.supportsTopP}
     * is {@code true} — otherwise omitted for that attempt and recorded in {@code droppedFeatures}
     * (§4).
     */
    Double topP;
}
