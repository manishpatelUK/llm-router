package com.manishpateluk.llmrouter.negotiation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manishpateluk.llmrouter.capability.ModelEntry;
import com.manishpateluk.llmrouter.config.StructuredOutputStrategy;
import com.manishpateluk.llmrouter.model.Attachment;
import com.manishpateluk.llmrouter.model.Request;

/**
 * Adapts an outgoing {@link Request} for one candidate model, per {@code LIBRARY_SPEC.md} §4.
 * Called fresh for every attempt — never cached across a fallback — since a different candidate
 * model may support a different subset of the requested features.
 */
public final class CapabilityNegotiator {

    private static final ObjectMapper SCHEMA_MAPPER = new ObjectMapper();

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
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(strategy, "strategy must not be null");

        List<String> droppedFeatures = new ArrayList<>();
        Request.RequestBuilder adapted = request.toBuilder();
        boolean structuredOutputViaPromptFallback = false;

        if (request.getResponseSchema() != null) {
            if (model.isSupportsStructuredOutput()) {
                // sent natively — request already carries responseSchema, nothing to change
            } else if (strategy == StructuredOutputStrategy.NATIVE) {
                droppedFeatures.add("responseSchema");
                adapted.responseSchema(null);
            } else {
                // PROMPT_FALLBACK or AUTO, with no native support: inject schema + instructions
                adapted.systemInstructions(withPromptFallbackInstruction(
                        request.getSystemInstructions(), request.getResponseSchema()));
                adapted.responseSchema(null);
                structuredOutputViaPromptFallback = true;
            }
        }

        if (!request.getTools().isEmpty() && !model.isSupportsTools()) {
            droppedFeatures.add("tools");
            adapted.tools(List.of());
        }

        if (!request.getAttachments().isEmpty() && !allAttachmentsSupported(request.getAttachments(), model)) {
            droppedFeatures.add("attachments");
            adapted.attachments(List.of());
        }

        // Providers universally recommend altering temperature OR top_p, never both, since combining
        // them compounds unpredictably — not a wire-protocol restriction, but this library enforces it:
        // when both are requested and the candidate model supports temperature, temperature wins.
        boolean preferTemperatureOverTopP = temperature != null && topP != null && model.isSupportsTemperature();

        if (temperature != null) {
            if (model.isSupportsTemperature()) {
                adapted.temperature(temperature);
            } else {
                droppedFeatures.add("temperature");
            }
        }

        if (topP != null) {
            if (model.isSupportsTopP() && !preferTemperatureOverTopP) {
                adapted.topP(topP);
            } else {
                droppedFeatures.add("topP");
            }
        }

        return NegotiationResult.builder()
                .adaptedRequest(adapted.build())
                .droppedFeatures(List.copyOf(droppedFeatures))
                .structuredOutputViaPromptFallback(structuredOutputViaPromptFallback)
                .build();
    }

    private static boolean allAttachmentsSupported(List<Attachment> attachments, ModelEntry model) {
        return attachments.stream().allMatch(attachment -> isAttachmentSupported(attachment, model));
    }

    private static boolean isAttachmentSupported(Attachment attachment, ModelEntry model) {
        String mediaType = attachment.getMediaType();
        if (mediaType != null && mediaType.startsWith("image/")) {
            return model.isSupportsVision();
        }
        return model.isSupportsFileInput();
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
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("responseSchema could not be serialized to JSON", e);
        }
    }
}
