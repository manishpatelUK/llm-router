package com.manishpateluk.llmrouter.model;

import com.manishpateluk.llmrouter.provider.Provider;

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
}
