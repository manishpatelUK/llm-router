package com.manishpateluk.llmrouter.model;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/** Token/cost accounting for one completed attempt — see {@code LIBRARY_SPEC.md} §3. */
@Value
@Builder(toBuilder = true)
@Jacksonized
public class Usage {

    int inputTokens;
    int outputTokens;
    int reasoningTokens;

    /**
     * Estimated cost in US cents (not dollars) — integral cents avoid floating-point drift.
     * Named {@code estimatedCostUsdCents} rather than the spec's literal {@code estimatedCostUsd}
     * field name, since that name misleadingly suggests a dollar-denominated value; the spec
     * itself notes the value is "converted to cents to avoid floating point issues".
     */
    int estimatedCostUsdCents;
}
