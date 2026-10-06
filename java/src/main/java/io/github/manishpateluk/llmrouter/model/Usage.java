package io.github.manishpateluk.llmrouter.model;

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

    /**
     * The same estimate in micro-dollars (millionths of a dollar), still integral. Whole cents
     * round most small calls to 0 — a 2,000-token embedding at $0.02 per million tokens costs
     * $0.00004 — so sum this field when tracking spend across many calls.
     */
    long estimatedCostUsdMicros;
}
