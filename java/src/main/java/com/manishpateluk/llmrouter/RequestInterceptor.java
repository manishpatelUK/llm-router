package com.manishpateluk.llmrouter;

import com.manishpateluk.llmrouter.model.Request;
import com.manishpateluk.llmrouter.provider.Provider;

/**
 * An optional last-chance hook to inspect or modify the final, negotiated {@link Request}
 * immediately before it's sent to a specific provider/model — see {@code LIBRARY_SPEC.md} §4.1.
 *
 * <p>Runs after capability negotiation (§4), so {@code request} is the fully adapted form
 * actually about to be sent for this attempt — not the original request the caller built. A
 * typical use is compressing conversation history against that exact model's context window
 * right before it leaves the process.
 *
 * <p>Called once per attempt in the fallback loop (§5.1): if an earlier candidate fails
 * structurally, this runs again for the next candidate with its own {@code provider}/{@code
 * model}. The same hook instance is shared across every call {@link LlmRouter} serves, for both
 * {@link LlmRouter#complete(Request)} and {@link LlmRouter#completeAsync(Request)}. If this
 * throws, the router treats it like any other in-attempt failure — the attempt is recorded as
 * failed and the router advances to the next candidate, exactly as an {@code adapter.send}
 * failure would.
 */
@FunctionalInterface
public interface RequestInterceptor {

    /**
     * @param provider the canonical provider id this attempt is about to be sent to
     * @param model the provider-defined model id this attempt is about to be sent to
     * @param request the fully negotiated request about to be sent
     * @return the request to actually send; returning {@code request} unchanged is a no-op
     */
    Request beforeSend(Provider provider, String model, Request request);
}
