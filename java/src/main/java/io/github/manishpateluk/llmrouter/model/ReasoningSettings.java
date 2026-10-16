package io.github.manishpateluk.llmrouter.model;

import io.github.manishpateluk.llmrouter.capability.ReasoningControl;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * The reasoning control actually sent to the provider for the attempt that answered — see
 * {@code LIBRARY_SPEC.md} §7.4. Exactly one of {@code effort} (for {@link ReasoningControl#EFFORT})
 * or {@code budgetTokens} (for {@link ReasoningControl#BUDGET}) is set.
 */
@Value
@Builder
@Jacksonized
public class ReasoningSettings {

    ReasoningControl control;

    /** The provider's own effort value, e.g. {@code "high"} or {@code "xhigh"}. */
    String effort;

    /** The thinking-token budget sent. */
    Integer budgetTokens;
}
