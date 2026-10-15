package io.github.manishpateluk.llmrouter.model;

import io.github.manishpateluk.llmrouter.config.ThinkingLevel;
import io.github.manishpateluk.llmrouter.provider.Provider;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/** Record of one candidate the router tried (or skipped) — see {@code LIBRARY_SPEC.md} §3. */
@Value
@Builder
@Jacksonized
public class AttemptRecord {

    Provider provider;
    String model;
    AttemptOutcome outcome;

    /** Error message or skip reason, e.g. {@code "no API key detected"}. */
    String reason;

    /**
     * The thinking level this candidate was resolved at — the requested level, or a lower one
     * when {@code FallbackPolicy.FINISH} stepped down (§5.1.2).
     */
    ThinkingLevel thinkingLevel;
}
