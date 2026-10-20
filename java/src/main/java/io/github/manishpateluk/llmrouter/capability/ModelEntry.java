package io.github.manishpateluk.llmrouter.capability;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.manishpateluk.llmrouter.provider.Provider;

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
 *
 * <p>Attachment support is described by {@link #getInputMediaTypes() inputMediaTypes}.
 * {@code supportsVision} and {@code supportsFileInput} remain readable, derived from it. A row
 * built the older way, with those two flags and no {@code inputMediaTypes}, keeps their old
 * meaning: any {@code image/*} type when {@code supportsVision}, any other type when
 * {@code supportsFileInput}.
 */
@Value
@Builder
@Jacksonized
public class ModelEntry {

    /** The image types a legacy {@code supportsVision} row is taken to accept. */
    static final List<String> LEGACY_IMAGE_TYPES = List.of("image/png", "image/jpeg", "image/webp", "image/gif");

    /** Canonical provider id — see {@code LIBRARY_SPEC.md} §12.1. */
    Provider provider;

    /** Provider-defined model id, e.g. {@code "claude-opus-5"} — not a library-level enum. */
    String model;

    double inputCostPerMillionTokens;
    double outputCostPerMillionTokens;

    /**
     * Price of input tokens read from the prompt cache, or {@code null} for the provider's usual
     * rate — see {@link #effectiveCacheReadCostPerMillionTokens()} and §7.1.
     */
    Double cacheReadCostPerMillionTokens;

    /**
     * Price of input tokens written to the prompt cache, or {@code null} for the provider's usual
     * rate — see {@link #effectiveCacheWriteCostPerMillionTokens()} and §7.1.
     */
    Double cacheWriteCostPerMillionTokens;

    /** 0-10, relative reasoning/capability strength within this table. */
    double thinkingScore;

    /** 0-10, relative latency/throughput — higher means faster. */
    double speedScore;

    int contextWindowTokens;
    int maxOutputTokens;

    boolean supportsStructuredOutput;
    boolean supportsTools;

    /**
     * Legacy input flag: accepts {@code image/*} attachments. Only consulted when
     * {@code inputMediaTypes} isn't set; read it through {@link #isSupportsVision()}.
     */
    boolean supportsVision;

    /**
     * Legacy input flag: accepts non-image attachments. Only consulted when
     * {@code inputMediaTypes} isn't set; read it through {@link #isSupportsFileInput()}.
     */
    boolean supportsFileInput;

    /**
     * The exact MIME types this model takes as request attachments, e.g. {@code "image/png"},
     * {@code "application/pdf"} — see §4 and §7.1. {@code null} means "described by the legacy
     * {@code supportsVision}/{@code supportsFileInput} flags instead".
     */
    List<String> inputMediaTypes;

    /** Most images one request may carry, or {@code null} if the provider publishes no limit. */
    Integer maxImagesPerRequest;

    /** Most pages one PDF may have, or {@code null} if the provider publishes no limit. */
    Integer maxPdfPages;

    /** Largest PDF, in bytes, or {@code null} if the provider publishes no limit. */
    Integer maxPdfBytes;

    /** Can produce downloadable generated files, e.g. via code execution or image generation — see §4. */
    boolean supportsFileOutput;

    /**
     * Accepts a caller-supplied sampling-temperature value — {@code false} if the model's API
     * rejects/ignores it (e.g. a reasoning mode with a fixed sampling configuration) — see §4.
     */
    boolean supportsTemperature;

    /** Accepts a caller-supplied nucleus-sampling (top-p) value, same rationale as {@link #supportsTemperature} — see §4. */
    boolean supportsTopP;

    /**
     * How the API lets a caller set this model's reasoning depth (§7.4); {@code null} reads as
     * {@link ReasoningControl#NONE}.
     */
    ReasoningControl reasoningControl;

    /**
     * For {@link ReasoningControl#EFFORT}: the effort values the model accepts, lowest first,
     * e.g. {@code ["low", "medium", "high", "xhigh", "max"]}. Off-switches such as
     * {@code "none"} aren't listed.
     */
    List<String> reasoningEffortLevels;

    /** UTC timestamp of when this specific row was last verified/updated. */
    Instant lastUpdated;

    /**
     * {@link #cacheReadCostPerMillionTokens} when set; otherwise a tenth of the input price for
     * Anthropic (its published rate for every model) and the full input price for anyone else,
     * since their cache discounts vary by model (§7.1).
     */
    public double effectiveCacheReadCostPerMillionTokens() {
        if (cacheReadCostPerMillionTokens != null) {
            return cacheReadCostPerMillionTokens;
        }
        return provider == Provider.ANTHROPIC ? inputCostPerMillionTokens * 0.1 : inputCostPerMillionTokens;
    }

    /**
     * {@link #cacheWriteCostPerMillionTokens} when set; otherwise 1.25 times the input price for
     * Anthropic (its rate for the default five-minute cache) and the full input price for anyone else (§7.1).
     */
    public double effectiveCacheWriteCostPerMillionTokens() {
        if (cacheWriteCostPerMillionTokens != null) {
            return cacheWriteCostPerMillionTokens;
        }
        return provider == Provider.ANTHROPIC ? inputCostPerMillionTokens * 1.25 : inputCostPerMillionTokens;
    }

    /** {@link #reasoningControl}, with {@code null} read as {@link ReasoningControl#NONE}. */
    public ReasoningControl getReasoningControl() {
        return reasoningControl == null ? ReasoningControl.NONE : reasoningControl;
    }

    /** {@link #reasoningEffortLevels}, never {@code null}. */
    public List<String> getReasoningEffortLevels() {
        return reasoningEffortLevels == null ? List.of() : reasoningEffortLevels;
    }

    /**
     * The media types this model takes; for a legacy row (no {@code inputMediaTypes}), the image
     * types when {@code supportsVision} and {@code application/pdf} when {@code supportsFileInput}.
     */
    public List<String> getInputMediaTypes() {
        if (inputMediaTypes != null) {
            return inputMediaTypes;
        }
        List<String> derived = new ArrayList<>();
        if (supportsVision) {
            derived.addAll(LEGACY_IMAGE_TYPES);
        }
        if (supportsFileInput) {
            derived.add("application/pdf");
        }
        return List.copyOf(derived);
    }

    /** Accepts at least one image type. */
    public boolean isSupportsVision() {
        return inputMediaTypes == null ? supportsVision : inputMediaTypes.stream().anyMatch(ModelEntry::isImageType);
    }

    /** Accepts at least one non-image (document) type. */
    public boolean isSupportsFileInput() {
        return inputMediaTypes == null ? supportsFileInput : inputMediaTypes.stream().anyMatch(type -> !isImageType(type));
    }

    /**
     * Whether this model takes an attachment of {@code mediaType}. Matching ignores case and any
     * parameters (so {@code "text/plain; charset=utf-8"} matches {@code "text/plain"}), and treats
     * {@code image/jpg} as {@code image/jpeg}. A legacy row keeps its old rule: {@code image/*}
     * when {@code supportsVision}, anything else when {@code supportsFileInput}.
     */
    public boolean supportsMediaType(String mediaType) {
        String normalized = normalizeMediaType(mediaType);
        if (inputMediaTypes == null) {
            return isImageType(normalized) ? supportsVision : supportsFileInput;
        }
        return inputMediaTypes.stream().map(ModelEntry::normalizeMediaType).anyMatch(normalized::equals);
    }

    /** Lower-cased, without parameters, with {@code image/jpg} read as {@code image/jpeg}; {@code ""} for {@code null}. */
    public static String normalizeMediaType(String mediaType) {
        if (mediaType == null) {
            return "";
        }
        String bare = mediaType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return bare.equals("image/jpg") ? "image/jpeg" : bare;
    }

    static boolean isImageType(String mediaType) {
        return mediaType != null && mediaType.toLowerCase(Locale.ROOT).startsWith("image/");
    }
}
