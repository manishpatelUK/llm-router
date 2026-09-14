package com.manishpateluk.llmrouter.capability;

import java.time.Instant;

import com.manishpateluk.llmrouter.provider.Provider;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * An immutable snapshot of a single provider/model row from the Model Capability Table.
 *
 * <p>Mirrors the canonical {@code ModelEntry} schema defined in {@code LIBRARY_SPEC.md} §7.1.
 * Instances are only ever constructed via {@code builder()} (directly, or by Jackson when
 * deserializing {@code model-capability-table.json}); there are no setters, so a reference to
 * one can be shared freely across threads.
 */
@Value
@Builder
@Jacksonized
public class ModelEntry {

    /** Canonical provider id — see {@code LIBRARY_SPEC.md} §12.1. */
    Provider provider;

    /** Provider-defined model id, e.g. {@code "claude-opus-5"} — not a library-level enum. */
    String model;

    double inputCostPerMillionTokens;
    double outputCostPerMillionTokens;

    /** 0-10, relative reasoning/capability strength within this table. */
    double thinkingScore;

    /** 0-10, relative latency/throughput — higher means faster. */
    double speedScore;

    int contextWindowTokens;
    int maxOutputTokens;

    boolean supportsStructuredOutput;
    boolean supportsTools;
    boolean supportsVision;

    /** Can accept non-image file attachments (documents, etc.) in a request — see §4. */
    boolean supportsFileInput;

    /** Can produce downloadable generated files, e.g. via code execution or image generation — see §4. */
    boolean supportsFileOutput;

    /** UTC timestamp of when this specific row was last verified/updated. */
    Instant lastUpdated;
}
