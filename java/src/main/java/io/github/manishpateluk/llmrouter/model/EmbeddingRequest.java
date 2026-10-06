package io.github.manishpateluk.llmrouter.model;

import io.github.manishpateluk.llmrouter.config.RouteEntry;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.List;

/**
 * Texts to turn into embedding vectors — see {@code LlmRouter.embed} and {@code LIBRARY_SPEC.md} §3.2.
 *
 * <p>{@code route} picks the embedding model(s). Vectors from different models live in different
 * spaces and can't be compared, so unlike chat, embeddings don't fall back across models unless
 * {@code route} lists more than one: with no route, the first available provider's default
 * embedding model is used, alone.
 */
@Value
@Builder(toBuilder = true)
public class EmbeddingRequest {

    /** Required, non-empty: the texts to embed, in order. */
    @Singular
    List<String> texts;

    /**
     * Optional: candidate provider/model pairs, tried in order. A provider-only entry uses that
     * provider's default embedding model. {@code null} means the first available provider that
     * offers embeddings, with its default model.
     */
    List<RouteEntry> route;

    /** Optional: requested vector length, for models that support shortening; {@code null} for the model's default. */
    Integer dimensions;
}
