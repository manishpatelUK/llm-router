package io.github.manishpateluk.llmrouter;

/**
 * Receives a response's text as it's generated, for {@link LlmRouter#completeStreaming}.
 *
 * <p>Fallback still applies while streaming: if an attempt fails after some text has already been
 * delivered, {@link #onReset()} is called before the router moves on to the next candidate, whose
 * text then streams from the beginning. A listener that shows text to a user should clear what it
 * has shown on reset.
 */
@FunctionalInterface
public interface StreamListener {

    /** The next piece of the response's text, in order. Never empty. */
    void onText(String delta);

    /** The text delivered so far belongs to a failed attempt and should be discarded. */
    default void onReset() {
    }
}
