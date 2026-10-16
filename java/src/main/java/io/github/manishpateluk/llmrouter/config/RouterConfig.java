package io.github.manishpateluk.llmrouter.config;

import java.util.List;
import java.util.Set;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * Reusable, long-lived routing configuration — see {@code LIBRARY_SPEC.md} §5. Applications
 * typically build one (or a small number of named ones) at startup and reuse it; it is
 * deliberately not rebuilt per request.
 *
 * <p>{@code thinkingLevel}, {@code costOptimized}, {@code structuredOutputStrategy},
 * {@code requiredFeatures}, {@code fallback}, and {@code sendThinkingLevel} always carry their spec-defined default when not explicitly set, so
 * downstream routing code never needs to null-check them. {@code route} is the one field that stays {@code null} by default —
 * unlike the others, "no route given" resolves dynamically from available providers (§5.2)
 * rather than to a fixed value, so a {@code RouterConfig.builder().build()} with no other
 * fields set behaves identically to no config being passed at all.
 *
 * <p>Use {@code toBuilder()} to derive a variant that changes one field and keeps the rest.
 */
@Value
@Builder(toBuilder = true)
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

    /**
     * Features that must never be dropped (§5.4). A candidate whose model can't honor one of
     * these for the request at hand is skipped and recorded in {@code attempts}, rather than
     * tried with the feature stripped. A required feature the request doesn't use is trivially
     * satisfied. Empty by default, which leaves routing exactly as it is without this field.
     */
    @Builder.Default
    Set<Feature> requiredFeatures = Set.of();

    /**
     * What to do once every candidate at {@code thinkingLevel} has failed or been skipped (§5.1.2):
     * {@code SAME_DEPTH} (the default) throws {@code RouterExhaustedException}; {@code FINISH} tries
     * provider-only entries and the default route again one level lower, down to {@code LOW}.
     */
    @Builder.Default
    FallbackPolicy fallback = FallbackPolicy.SAME_DEPTH;

    /**
     * Whether to send the thinking level to the chosen model's own reasoning control (an effort
     * level or a thinking budget, per its {@code ModelEntry.reasoningControl}), not only use it to
     * choose the model (§7.4). Off by default, which leaves every model on its provider's default
     * reasoning, as before this field existed.
     */
    @Builder.Default
    boolean sendThinkingLevel = false;
}
