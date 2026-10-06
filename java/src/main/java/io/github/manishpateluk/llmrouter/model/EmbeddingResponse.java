package io.github.manishpateluk.llmrouter.model;

import io.github.manishpateluk.llmrouter.provider.Provider;
import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * The result of {@code LlmRouter.embed}: one vector per input text, in input order, plus which
 * model produced them (store it beside the vectors — only vectors from the same model are
 * comparable) and the tokens used.
 */
@Value
@Builder(toBuilder = true)
public class EmbeddingResponse {

    List<float[]> vectors;

    Provider providerUsed;

    String modelUsed;

    /**
     * Input tokens consumed, and the cost estimated from the capability table's embedding prices
     * (0 for a model the table doesn't price). Read {@code estimatedCostUsdMicros}: embedding calls
     * usually cost well under a cent, so {@code estimatedCostUsdCents} is usually 0.
     */
    Usage usage;

    /** Earlier candidates that were skipped or failed, if {@code route} listed several. */
    @Builder.Default
    List<AttemptRecord> attempts = List.of();
}
