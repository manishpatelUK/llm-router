package io.github.manishpateluk.llmrouter;

import io.github.manishpateluk.llmrouter.model.AttemptUsage;
import io.github.manishpateluk.llmrouter.model.CallUsage;

/**
 * Hears about every attempt and every call an {@link LlmRouter} makes, for metrics, spend tracking
 * or logs — see {@code LIBRARY_SPEC.md} §5.6. Install one with {@link LlmRouter#withUsageListener}.
 *
 * <p>{@link #onAttempt} is called once per candidate the router tried or skipped, in order, and
 * {@link #onCall} once when the call ends, whether it succeeded or failed. Both cover completions
 * (sync, async and streaming) and embeddings. Calls that fail validation before any candidate is
 * considered aren't reported.
 *
 * <p>Methods are called on the thread that finished the attempt, which for async calls is a
 * background thread, so an implementation shared across calls must be thread-safe. They should
 * return quickly: the call waits for them. An exception thrown here is logged and otherwise
 * ignored, so a broken listener never fails a call.
 */
public interface UsageListener {

    /** One candidate's outcome: succeeded, failed after any retries, or skipped. */
    default void onAttempt(AttemptUsage attempt) {
    }

    /** The whole call's outcome, after its last attempt. */
    default void onCall(CallUsage call) {
    }
}
