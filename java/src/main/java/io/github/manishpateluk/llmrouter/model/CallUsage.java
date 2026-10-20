package io.github.manishpateluk.llmrouter.model;

import java.time.Duration;
import java.util.List;

import io.github.manishpateluk.llmrouter.provider.Provider;

import lombok.Builder;
import lombok.Value;

/** What one whole call used, for {@code UsageListener.onCall} — see {@code LIBRARY_SPEC.md} §5.6. */
@Value
@Builder
public class CallUsage {

    /** Whether a candidate answered. */
    boolean succeeded;

    /** Who answered; {@code null} when the call failed. */
    Provider providerUsed;

    /** The model that answered; {@code null} when the call failed. */
    String modelUsed;

    /** From the start of the first attempt's routing until the call ended. */
    Duration latency;

    /** The answering attempt's tokens and estimated cost; {@code null} when the call failed. */
    Usage usage;

    /** Every candidate the router tried or skipped, in order, the answering one last. */
    List<AttemptUsage> attempts;

    /** Why the call failed, as thrown to the caller; {@code null} on success. */
    Throwable failure;

    /** Whether this was an embedding call rather than a completion. */
    boolean embedding;
}
