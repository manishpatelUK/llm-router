package io.github.manishpateluk.llmrouter.model;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/** Token/cost accounting for one completed attempt — see {@code LIBRARY_SPEC.md} §3. */
@Value
@Builder(toBuilder = true)
@Jacksonized
public class Usage {

    /** Every input token the model read, including the ones served from or written to the provider's prompt cache. */
    int inputTokens;

    /** Output tokens, including any reasoning tokens. */
    int outputTokens;

    /**
     * The part of {@code outputTokens} spent on reasoning, where the provider reports it (OpenAI,
     * Perplexity and OpenAI-compatible providers that return it); 0 when it isn't reported.
     */
    int reasoningTokens;

    /**
     * The part of {@code inputTokens} read from the provider's prompt cache (§4.3), which is billed
     * at a discount; 0 when nothing was cached or the provider doesn't report it.
     */
    int cacheReadTokens;

    /**
     * The part of {@code inputTokens} written to the provider's prompt cache on this call (§4.3).
     * Only providers that bill cache writes separately report it (Anthropic, at a premium); 0 otherwise.
     */
    int cacheWriteTokens;

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
