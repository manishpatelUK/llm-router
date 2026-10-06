package io.github.manishpateluk.llmrouter.capability;

import java.time.Instant;

import io.github.manishpateluk.llmrouter.provider.Provider;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * One embedding model's row in the Model Capability Table — see {@code LIBRARY_SPEC.md} §7.3.
 *
 * <p>Kept apart from {@link ModelEntry} because embedding models are never chat candidates: they
 * have no thinking/speed scores or chat capability flags, and chat routing must never pick one.
 * {@code LlmRouter.embed} uses these rows only to price a call.
 */
@Value
@Builder
@Jacksonized
public class EmbeddingModelEntry {

    /** Canonical provider id — see {@code LIBRARY_SPEC.md} §12.1. */
    Provider provider;

    /** Provider-defined model id, e.g. {@code "text-embedding-3-small"}. */
    String model;

    /** USD per million input tokens, standard (non-batch) rate. Embeddings have no output tokens. */
    double inputCostPerMillionTokens;

    /** Default vector length (some models can be asked for shorter vectors). */
    int dimensions;

    /** Most tokens one input text may contain. */
    int maxInputTokens;

    /** UTC timestamp of when this specific row was last verified/updated. */
    Instant lastUpdated;
}
