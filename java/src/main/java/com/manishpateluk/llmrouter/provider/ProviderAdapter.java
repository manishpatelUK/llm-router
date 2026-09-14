package com.manishpateluk.llmrouter.provider;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.manishpateluk.llmrouter.model.Request;
import com.manishpateluk.llmrouter.model.Response;

/**
 * Translates the unified {@link Request}/{@link Response} shapes to/from one provider's wire
 * format — see {@code LIBRARY_SPEC.md} §8.1. All routing, fallback, capability negotiation, and
 * cost logic lives in the router core and is shared across every provider; an adapter's only
 * job is the request/response translation for the one provider it implements, and reporting
 * whether it currently has usable credentials.
 *
 * <p>{@link #send} and {@link #sendAsync} are always called with an already-negotiated request
 * (see {@code CapabilityNegotiator}) for one specific, fully-resolved model id — an adapter
 * never needs to consult the Model Capability Table itself to decide what it can send, only to
 * translate it.
 *
 * <p>The {@link Response} an adapter returns is a <em>fragment</em>: {@code content},
 * {@code toolCalls}, {@code usage} (raw token counts only — cost is filled in by the router core
 * from the Model Capability Table), {@code generatedFiles}, and {@code original} are populated;
 * {@code providerUsed}, {@code modelUsed}, {@code attempts}, {@code droppedFeatures}, and
 * {@code structuredOutput} are left at their defaults and filled in by the router core.
 */
public interface ProviderAdapter {

    /**
     * Shared fallback executor for {@link #sendAsync}'s default implementation — one virtual
     * thread per call. Adapters backed by an SDK with a genuinely non-blocking async client (or
     * by non-blocking HTTP I/O) override {@link #sendAsync} instead of using this.
     */
    ExecutorService DEFAULT_ASYNC_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /** The canonical provider id this adapter implements. */
    Provider id();

    /** Credential check — see {@code LIBRARY_SPEC.md} §6. */
    boolean isAvailable();

    /**
     * Sends one already-negotiated request to {@code model} and returns the response fragment,
     * blocking the calling thread until it completes.
     *
     * @throws RuntimeException (provider/SDK-specific) if the call fails — the router core
     *         catches this and records the attempt as failed, per §5.1.1
     */
    Response send(String model, Request adaptedRequest);

    /**
     * Async counterpart of {@link #send}. The default implementation runs {@link #send} on a
     * fresh virtual thread and completes the returned future when it finishes — the "spin up a
     * thread and call back when done" fallback for a provider with no native non-blocking path.
     * Override this when the provider offers real non-blocking I/O (an async SDK client, or
     * non-blocking HTTP) instead.
     */
    default CompletableFuture<Response> sendAsync(String model, Request adaptedRequest) {
        return CompletableFuture.supplyAsync(() -> send(model, adaptedRequest), DEFAULT_ASYNC_EXECUTOR);
    }
}
