package io.github.manishpateluk.llmrouter.model;

import java.time.Duration;

import io.github.manishpateluk.llmrouter.config.ThinkingLevel;
import io.github.manishpateluk.llmrouter.provider.Provider;

import lombok.Builder;
import lombok.Value;

/** What one attempt used, for {@code UsageListener.onAttempt} — see {@code LIBRARY_SPEC.md} §5.6. */
@Value
@Builder
public class AttemptUsage {

    Provider provider;
    String model;
    AttemptOutcome outcome;

    /** The error message or skip reason, as in {@link AttemptRecord#getReason()}; {@code null} on success. */
    String reason;

    /** The thinking level the candidate was resolved at; {@code null} for embeddings. */
    ThinkingLevel thinkingLevel;

    /** Retries made on this candidate (§5.5); 0 when none. */
    int retries;

    /**
     * From just before the request went to the provider until its outcome, including any retries
     * and the waits between them. {@link Duration#ZERO} for a skipped candidate.
     */
    Duration latency;

    /**
     * Tokens and estimated cost, on success; {@code null} otherwise, since a failed attempt reports
     * no usage even if the provider billed for some of it.
     */
    Usage usage;

    /** Whether this was an embedding call rather than a completion. */
    boolean embedding;
}
