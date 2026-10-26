package io.github.manishpateluk.llmrouter.negotiation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import io.github.manishpateluk.llmrouter.capability.ModelEntry;
import io.github.manishpateluk.llmrouter.capability.ReasoningControl;
import io.github.manishpateluk.llmrouter.config.Feature;
import io.github.manishpateluk.llmrouter.config.StructuredOutputStrategy;
import io.github.manishpateluk.llmrouter.config.ThinkingLevel;
import io.github.manishpateluk.llmrouter.model.Attachment;
import io.github.manishpateluk.llmrouter.model.DroppedAttachment;
import io.github.manishpateluk.llmrouter.model.ReasoningSettings;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.routing.AttachmentInspector;

/**
 * Adapts an outgoing {@link Request} for one candidate model, per {@code LIBRARY_SPEC.md} §4.
 * Called fresh for every attempt — never cached across a fallback — since a different candidate
 * model may support a different subset of the requested features.
 */
public final class CapabilityNegotiator {

    private static final ObjectMapper SCHEMA_MAPPER = new ObjectMapper();

    /** Smallest thinking budget worth sending; Anthropic rejects anything lower (§7.4). */
    public static final int MIN_REASONING_BUDGET_TOKENS = 1_024;

    private CapabilityNegotiator() {
    }

    /**
     * @param strategy how to handle an unsupported {@code responseSchema}; see §4 and §12.4
     */
    public static NegotiationResult negotiate(ModelEntry model, Request request, StructuredOutputStrategy strategy) {
        return negotiate(model, request, strategy, null, null);
    }

    /**
     * @param strategy how to handle an unsupported {@code responseSchema}; see §4 and §12.4
     * @param temperature {@code RouterConfig.temperature} (§5); {@code null} if not requested
     * @param topP {@code RouterConfig.topP} (§5); {@code null} if not requested. If both
     *        {@code temperature} and {@code topP} are non-null, at most one is ever sent —
     *        whichever the candidate model supports, preferring {@code temperature} if it
     *        supports both — since providers universally recommend against combining them.
     */
    public static NegotiationResult negotiate(
            ModelEntry model, Request request, StructuredOutputStrategy strategy, Double temperature, Double topP) {
        return negotiate(model, request, strategy, temperature, topP, true);
    }

    /**
     * {@link #negotiate(ModelEntry, Request, StructuredOutputStrategy, Double, Double)}, where
     * {@code schemaSupportedNatively} {@code false} means the provider can't enforce this
     * particular {@code responseSchema} natively (see {@code ProviderAdapter.responseSchemaProblem}),
     * so it's handled as for a model without native structured output.
     */
    public static NegotiationResult negotiate(
            ModelEntry model, Request request, StructuredOutputStrategy strategy, Double temperature, Double topP,
            boolean schemaSupportedNatively) {
        return negotiate(model, request, strategy, temperature, topP, schemaSupportedNatively, null);
    }

    /**
     * {@link #negotiate(ModelEntry, Request, StructuredOutputStrategy, Double, Double, boolean)},
     * also sending {@code reasoningLevel} to the model's own reasoning control (§7.4) —
     * {@code null} sends none, leaving the provider's default. A thinking budget switches
     * {@code temperature} and {@code topP} off for the attempt (dropped and recorded), since
     * budget-style extended thinking doesn't allow changing them.
     */
    public static NegotiationResult negotiate(
            ModelEntry model, Request request, StructuredOutputStrategy strategy, Double temperature, Double topP,
            boolean schemaSupportedNatively, ThinkingLevel reasoningLevel) {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(strategy, "strategy must not be null");

        List<String> droppedFeatures = new ArrayList<>();
        Request.RequestBuilder adapted = request.toBuilder();
        boolean structuredOutputViaPromptFallback = false;

        if (request.getResponseSchema() != null && !(model.isSupportsStructuredOutput() && schemaSupportedNatively)) {
            adapted.responseSchema(null);
            String fallbackInstructions = promptFallbackInstructions(request, strategy, droppedFeatures);
            if (fallbackInstructions != null) {
                adapted.systemInstructions(fallbackInstructions);
                structuredOutputViaPromptFallback = true;
            }
        }

        if (!request.getTools().isEmpty() && !model.isSupportsTools()) {
            droppedFeatures.add("tools");
            adapted.tools(List.of());
        }

        List<DroppedAttachment> droppedAttachments = new ArrayList<>();
        if (request.hasAttachments()) {
            // Positions in request.everyAttachment(): the history's attachments in order, then the prompt's.
            boolean[] kept = keptAttachments(request.everyAttachment(), model, droppedAttachments);
            if (!droppedAttachments.isEmpty()) {
                droppedFeatures.add("attachments");
                int position = 0;
                List<io.github.manishpateluk.llmrouter.model.Message> history = new ArrayList<>();
                for (io.github.manishpateluk.llmrouter.model.Message message : request.getHistory()) {
                    if (message.getAttachments().isEmpty()) {
                        history.add(message);
                        continue;
                    }
                    List<Attachment> keptHere = new ArrayList<>();
                    for (Attachment attachment : message.getAttachments()) {
                        if (kept[position++]) {
                            keptHere.add(attachment);
                        }
                    }
                    history.add(message.toBuilder().attachments(List.copyOf(keptHere)).build());
                }
                List<Attachment> keptWithPrompt = new ArrayList<>();
                for (Attachment attachment : request.getAttachments()) {
                    if (kept[position++]) {
                        keptWithPrompt.add(attachment);
                    }
                }
                adapted.history(List.copyOf(history));
                adapted.attachments(List.copyOf(keptWithPrompt));
            }
        }

        ReasoningSettings reasoning = reasoningLevel == null ? null : reasoningFor(model, request, reasoningLevel);
        if (reasoning != null) {
            adapted.reasoningEffort(reasoning.getEffort());
            adapted.reasoningBudgetTokens(reasoning.getBudgetTokens());
        }
        boolean samplingLocked = reasoning != null && reasoning.getControl() == ReasoningControl.BUDGET;

        // Providers universally recommend altering temperature OR top_p, never both, since combining
        // them compounds unpredictably — not a wire-protocol restriction, but this library enforces it:
        // when both are requested and the candidate model supports temperature, temperature wins.
        boolean preferTemperatureOverTopP = temperature != null && topP != null && model.isSupportsTemperature();

        if (temperature != null) {
            if (model.isSupportsTemperature() && !samplingLocked) {
                adapted.temperature(temperature);
            } else {
                droppedFeatures.add("temperature");
            }
        }

        if (topP != null) {
            if (model.isSupportsTopP() && !preferTemperatureOverTopP && !samplingLocked) {
                adapted.topP(topP);
            } else {
                droppedFeatures.add("topP");
            }
        }

        return NegotiationResult.builder()
                .adaptedRequest(adapted.build())
                .droppedFeatures(List.copyOf(droppedFeatures))
                .droppedAttachments(List.copyOf(droppedAttachments))
                .reasoning(reasoning)
                .structuredOutputViaPromptFallback(structuredOutputViaPromptFallback)
                .build();
    }

    /**
     * Negotiation for a model missing from the capability table: with no data to check against,
     * everything is sent optimistically (temperature and topP included), except a
     * {@code responseSchema} the provider is known not to support natively
     * ({@code schemaSupportedNatively} {@code false}), which is handled as for a model without
     * native structured output.
     */
    public static NegotiationResult negotiateUnknownModel(
            Request request, StructuredOutputStrategy strategy, Double temperature, Double topP,
            boolean schemaSupportedNatively) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(strategy, "strategy must not be null");
        List<String> droppedFeatures = new ArrayList<>();
        Request.RequestBuilder adapted = request.toBuilder().temperature(temperature).topP(topP);
        boolean viaPromptFallback = false;
        if (request.getResponseSchema() != null && !schemaSupportedNatively) {
            adapted.responseSchema(null);
            String fallbackInstructions = promptFallbackInstructions(request, strategy, droppedFeatures);
            if (fallbackInstructions != null) {
                adapted.systemInstructions(fallbackInstructions);
                viaPromptFallback = true;
            }
        }
        return NegotiationResult.builder()
                .adaptedRequest(adapted.build())
                .droppedFeatures(List.copyOf(droppedFeatures))
                .structuredOutputViaPromptFallback(viaPromptFallback)
                .build();
    }

    /**
     * A {@code responseSchema} the model can't take natively: dropped under {@code NATIVE}
     * (returns {@code null}), else the system instructions with the schema put into them.
     * Takes no {@code Request.RequestBuilder}: javadoc runs on the un-delomboked sources, where
     * that type doesn't exist, so it must stay out of method signatures.
     */
    private static String promptFallbackInstructions(
            Request request, StructuredOutputStrategy strategy, List<String> droppedFeatures) {
        if (strategy == StructuredOutputStrategy.NATIVE) {
            droppedFeatures.add("responseSchema");
            return null;
        }
        // PROMPT_FALLBACK or AUTO, with no native support: inject schema + instructions
        return withPromptFallbackInstruction(request.getSystemInstructions(), request.getResponseSchema());
    }

    /**
     * The request with {@code responseSchema} moved into its system instructions, exactly as the
     * {@code PROMPT_FALLBACK} path sends it — for an adapter whose provider rejects a schema only
     * once it's sent, so the router couldn't move it beforehand.
     */
    public static Request withSchemaInPrompt(Request request) {
        Objects.requireNonNull(request.getResponseSchema(), "request has no responseSchema");
        return request.toBuilder()
                .responseSchema(null)
                .systemInstructions(withPromptFallbackInstruction(request.getSystemInstructions(), request.getResponseSchema()))
                .build();
    }

    /**
     * The members of {@code required} that negotiating {@code request} against {@code model}
     * would drop (§5.4), in {@link Feature} declaration order — empty if the model can honor
     * every required feature the request actually uses. Defined in terms of {@link #negotiate}
     * itself, so "required" always means exactly "would otherwise appear in
     * {@code droppedFeatures}": a {@code responseSchema} honored via prompt-fallback counts as
     * honored, and a required feature the request doesn't use is never unmet.
     */
    public static List<Feature> unmetRequiredFeatures(
            ModelEntry model, Request request, StructuredOutputStrategy strategy, Set<Feature> required) {
        return unmetRequiredFeatures(model, request, strategy, required, true);
    }

    /** {@link #unmetRequiredFeatures(ModelEntry, Request, StructuredOutputStrategy, Set)} with {@code schemaSupportedNatively} as in {@link #negotiate}. */
    public static List<Feature> unmetRequiredFeatures(
            ModelEntry model, Request request, StructuredOutputStrategy strategy, Set<Feature> required,
            boolean schemaSupportedNatively) {
        Objects.requireNonNull(required, "required must not be null");
        if (required.isEmpty()) {
            return List.of();
        }
        List<String> dropped = negotiate(model, request, strategy, null, null, schemaSupportedNatively).getDroppedFeatures();
        return Arrays.stream(Feature.values())
                .filter(required::contains)
                .filter(feature -> dropped.contains(feature.wireValue()))
                .toList();
    }

    /**
     * Which of {@code attachments} {@code model} can take, by position, adding one {@link DroppedAttachment} per
     * other attachment to {@code dropped}: a media type it doesn't accept, an image beyond its
     * per-request image limit, or a PDF over its byte or page limit. Page limits apply only when
     * the PDF's page count can be read.
     */
    private static boolean[] keptAttachments(List<Attachment> attachments, ModelEntry model, List<DroppedAttachment> dropped) {
        boolean[] kept = new boolean[attachments.size()];
        int images = 0;
        for (int i = 0; i < attachments.size(); i++) {
            Attachment attachment = attachments.get(i);
            String mediaType = ModelEntry.normalizeMediaType(attachment.getMediaType());
            int bytes = attachment.getData() == null ? 0 : attachment.getData().length;
            String reason = null;
            if (!model.supportsMediaType(attachment.getMediaType())) {
                reason = "model does not accept " + (mediaType.isEmpty() ? "attachments with no media type" : mediaType);
            } else if (mediaType.startsWith("image/") && model.getMaxImagesPerRequest() != null
                    && images >= model.getMaxImagesPerRequest()) {
                reason = "over the model's limit of " + model.getMaxImagesPerRequest() + " images per request";
            } else if (mediaType.equals("application/pdf") && model.getMaxPdfBytes() != null && bytes > model.getMaxPdfBytes()) {
                reason = "PDF is " + bytes + " bytes, over the model's limit of " + model.getMaxPdfBytes();
            } else if (mediaType.equals("application/pdf") && model.getMaxPdfPages() != null) {
                int pages = AttachmentInspector.pdfPageCount(attachment.getData());
                if (pages > model.getMaxPdfPages()) {
                    reason = "PDF has " + pages + " pages, over the model's limit of " + model.getMaxPdfPages();
                }
            }
            if (reason == null) {
                kept[i] = true;
                if (mediaType.startsWith("image/")) {
                    images++;
                }
            } else {
                dropped.add(DroppedAttachment.builder()
                        .index(i)
                        .mediaType(attachment.getMediaType())
                        .filename(attachment.getFilename())
                        .reason(reason)
                        .build());
            }
        }
        return kept;
    }

    /**
     * The reasoning control to send {@code model} for {@code level} (§7.4), or {@code null} for
     * none. Effort: the model's own level of the same name, or the nearest by position in its
     * {@code reasoningEffortLevels}; {@code MAX} is always its highest. Budget: {@code LOW} sends
     * none (the provider default, no extended thinking); {@code MEDIUM}, {@code HIGH} and
     * {@code MAX} send 4,096, 16,384 and 32,768 tokens, capped at half the attempt's output limit so
     * the answer keeps room, and none at all when that cap falls below
     * {@link #MIN_REASONING_BUDGET_TOKENS}.
     */
    public static ReasoningSettings reasoningFor(ModelEntry model, Request request, ThinkingLevel level) {
        Objects.requireNonNull(level, "level must not be null");
        switch (model.getReasoningControl()) {
            case EFFORT -> {
                List<String> levels = model.getReasoningEffortLevels();
                if (levels.isEmpty()) {
                    return null;
                }
                return ReasoningSettings.builder().control(ReasoningControl.EFFORT).effort(effortFor(level, levels)).build();
            }
            case BUDGET -> {
                int budget = switch (level) {
                    case LOW -> 0;
                    case MEDIUM -> 4_096;
                    case HIGH -> 16_384;
                    case MAX -> 32_768;
                };
                int outputLimit = model.getMaxOutputTokens() > 0 ? model.getMaxOutputTokens() : Integer.MAX_VALUE;
                if (request.getMaxOutputTokens() != null) {
                    outputLimit = Math.min(outputLimit, request.getMaxOutputTokens());
                }
                budget = Math.min(budget, outputLimit / 2);
                if (budget < MIN_REASONING_BUDGET_TOKENS) {
                    return null;
                }
                return ReasoningSettings.builder().control(ReasoningControl.BUDGET).budgetTokens(budget).build();
            }
            default -> {
                return null;
            }
        }
    }

    private static String effortFor(ThinkingLevel level, List<String> levels) {
        int last = levels.size() - 1;
        if (level == ThinkingLevel.MAX) {
            return levels.get(last);
        }
        if (levels.contains(level.wireValue())) {
            return level.wireValue();
        }
        return switch (level) {
            case LOW -> levels.get(0);
            case MEDIUM -> levels.get(last / 2);
            default -> levels.get(Math.max(0, last - 1));
        };
    }

    private static String withPromptFallbackInstruction(String existingSystemInstructions, java.util.Map<String, Object> schema) {
        String instruction = "Respond with valid JSON only, matching this schema exactly. "
                + "Do not include any explanation, markdown formatting, or text outside the JSON object.\n\nSchema:\n"
                + writeSchemaAsJson(schema);
        if (existingSystemInstructions == null || existingSystemInstructions.isBlank()) {
            return instruction;
        }
        return existingSystemInstructions + "\n\n" + instruction;
    }

    private static String writeSchemaAsJson(java.util.Map<String, Object> schema) {
        try {
            return SCHEMA_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(schema);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("responseSchema could not be serialized to JSON", e);
        }
    }
}
