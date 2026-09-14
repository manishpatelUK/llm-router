package com.manishpateluk.llmrouter.routing;

/**
 * A rough, provider-agnostic token-count estimator used only for the relative cost ranking in
 * {@code LIBRARY_SPEC.md} §5.3 ("the prompt's approximate token count") — not a real tokenizer,
 * and not intended for precise cost prediction. The spec doesn't mandate a specific tokenizer;
 * pulling in a real one per provider is out of scope for this heuristic.
 */
public final class TokenEstimator {

    private static final double CHARS_PER_TOKEN = 4.0;

    private TokenEstimator() {
    }

    /** @return an approximate token count for {@code text}, using a chars-per-token heuristic */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }
}
