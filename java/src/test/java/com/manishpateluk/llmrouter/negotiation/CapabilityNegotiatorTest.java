package com.manishpateluk.llmrouter.negotiation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.manishpateluk.llmrouter.capability.ModelEntry;
import com.manishpateluk.llmrouter.config.StructuredOutputStrategy;
import com.manishpateluk.llmrouter.model.Attachment;
import com.manishpateluk.llmrouter.model.Request;
import com.manishpateluk.llmrouter.provider.Provider;

import org.junit.jupiter.api.Test;

class CapabilityNegotiatorTest {

    private static final Map<String, Object> SCHEMA = Map.of("type", "object", "properties", Map.of());

    @Test
    void requestWithNoOptionalFeaturesHasNothingDropped() {
        Request request = Request.builder().prompt("hello").build();
        ModelEntry model = fixture(false, false, false, false);

        NegotiationResult result = CapabilityNegotiator.negotiate(model, request, StructuredOutputStrategy.AUTO);

        assertThat(result.getDroppedFeatures()).isEmpty();
        assertThat(result.isStructuredOutputViaPromptFallback()).isFalse();
        assertThat(result.getAdaptedRequest().getPrompt()).isEqualTo("hello");
    }

    @Test
    void schemaSentNativelyWhenSupportedRegardlessOfStrategy() {
        Request request = Request.builder().prompt("p").responseSchema(SCHEMA).build();
        ModelEntry model = fixture(true, false, false, false);

        for (StructuredOutputStrategy strategy : StructuredOutputStrategy.values()) {
            NegotiationResult result = CapabilityNegotiator.negotiate(model, request, strategy);

            assertThat(result.getDroppedFeatures()).isEmpty();
            assertThat(result.isStructuredOutputViaPromptFallback()).isFalse();
            assertThat(result.getAdaptedRequest().getResponseSchema()).isEqualTo(SCHEMA);
        }
    }

    @Test
    void schemaDroppedUnderNativeStrategyWhenUnsupported() {
        Request request = Request.builder().prompt("p").responseSchema(SCHEMA).build();
        ModelEntry model = fixture(false, false, false, false);

        NegotiationResult result = CapabilityNegotiator.negotiate(model, request, StructuredOutputStrategy.NATIVE);

        assertThat(result.getDroppedFeatures()).containsExactly("responseSchema");
        assertThat(result.isStructuredOutputViaPromptFallback()).isFalse();
        assertThat(result.getAdaptedRequest().getResponseSchema()).isNull();
    }

    @Test
    void schemaInjectedAsPromptFallbackWhenUnsupportedUnderPromptFallbackStrategy() {
        Request request = Request.builder().prompt("p").systemInstructions("Be nice.").responseSchema(SCHEMA).build();
        ModelEntry model = fixture(false, false, false, false);

        NegotiationResult result = CapabilityNegotiator.negotiate(model, request, StructuredOutputStrategy.PROMPT_FALLBACK);

        assertThat(result.getDroppedFeatures()).isEmpty();
        assertThat(result.isStructuredOutputViaPromptFallback()).isTrue();
        assertThat(result.getAdaptedRequest().getResponseSchema()).isNull();
        assertThat(result.getAdaptedRequest().getSystemInstructions())
                .startsWith("Be nice.")
                .contains("JSON")
                .contains("\"type\"");
    }

    @Test
    void autoStrategyBehavesLikePromptFallbackWhenUnsupported() {
        Request request = Request.builder().prompt("p").responseSchema(SCHEMA).build();
        ModelEntry model = fixture(false, false, false, false);

        NegotiationResult result = CapabilityNegotiator.negotiate(model, request, StructuredOutputStrategy.AUTO);

        assertThat(result.isStructuredOutputViaPromptFallback()).isTrue();
        assertThat(result.getDroppedFeatures()).isEmpty();
    }

    @Test
    void toolsKeptWhenSupportedDroppedWhenNot() {
        Request request = Request.builder().prompt("p")
                .tools(List.of(com.manishpateluk.llmrouter.model.ToolDefinition.builder()
                        .name("lookup").description("d").parameters(Map.of()).build()))
                .build();

        NegotiationResult supported = CapabilityNegotiator.negotiate(fixture(false, true, false, false), request, StructuredOutputStrategy.AUTO);
        assertThat(supported.getDroppedFeatures()).isEmpty();
        assertThat(supported.getAdaptedRequest().getTools()).hasSize(1);

        NegotiationResult unsupported = CapabilityNegotiator.negotiate(fixture(false, false, false, false), request, StructuredOutputStrategy.AUTO);
        assertThat(unsupported.getDroppedFeatures()).containsExactly("tools");
        assertThat(unsupported.getAdaptedRequest().getTools()).isEmpty();
    }

    @Test
    void imageAttachmentGatedBySupportsVision() {
        Request request = Request.builder().prompt("p")
                .attachments(List.of(Attachment.builder().mediaType("image/png").data(new byte[]{1}).build()))
                .build();

        NegotiationResult supported = CapabilityNegotiator.negotiate(fixture(false, false, true, false), request, StructuredOutputStrategy.AUTO);
        assertThat(supported.getDroppedFeatures()).isEmpty();

        NegotiationResult unsupported = CapabilityNegotiator.negotiate(fixture(false, false, false, false), request, StructuredOutputStrategy.AUTO);
        assertThat(unsupported.getDroppedFeatures()).containsExactly("attachments");
        assertThat(unsupported.getAdaptedRequest().getAttachments()).isEmpty();
    }

    @Test
    void documentAttachmentGatedBySupportsFileInputNotVision() {
        Request request = Request.builder().prompt("p")
                .attachments(List.of(Attachment.builder().mediaType("application/pdf").data(new byte[]{1}).build()))
                .build();

        // Vision support alone does not cover a non-image document attachment
        ModelEntry visionOnly = fixture(false, false, true, false);
        NegotiationResult droppedForVisionOnly = CapabilityNegotiator.negotiate(visionOnly, request, StructuredOutputStrategy.AUTO);
        assertThat(droppedForVisionOnly.getDroppedFeatures()).containsExactly("attachments");

        ModelEntry fileInputCapable = fixture(false, false, false, true);
        NegotiationResult kept = CapabilityNegotiator.negotiate(fileInputCapable, request, StructuredOutputStrategy.AUTO);
        assertThat(kept.getDroppedFeatures()).isEmpty();
    }

    @Test
    void mixedAttachmentsDropWholeListIfAnyUnsupported() {
        Request request = Request.builder().prompt("p")
                .attachments(List.of(
                        Attachment.builder().mediaType("image/png").data(new byte[]{1}).build(),
                        Attachment.builder().mediaType("application/pdf").data(new byte[]{2}).build()))
                .build();
        // Supports vision but not general file input -> the PDF can't be sent, so the whole list drops
        ModelEntry model = fixture(false, false, true, false);

        NegotiationResult result = CapabilityNegotiator.negotiate(model, request, StructuredOutputStrategy.AUTO);

        assertThat(result.getDroppedFeatures()).containsExactly("attachments");
        assertThat(result.getAdaptedRequest().getAttachments()).isEmpty();
    }

    @Test
    void negotiationIsIndependentPerAttemptAndDoesNotMutateOriginalRequest() {
        Request original = Request.builder().prompt("p")
                .tools(List.of(com.manishpateluk.llmrouter.model.ToolDefinition.builder()
                        .name("t").description("d").parameters(Map.of()).build()))
                .build();

        NegotiationResult first = CapabilityNegotiator.negotiate(fixture(false, false, false, false), original, StructuredOutputStrategy.AUTO);
        NegotiationResult second = CapabilityNegotiator.negotiate(fixture(false, true, false, false), original, StructuredOutputStrategy.AUTO);

        assertThat(first.getDroppedFeatures()).containsExactly("tools");
        assertThat(second.getDroppedFeatures()).isEmpty();
        assertThat(original.getTools()).hasSize(1); // original untouched
    }

    private static ModelEntry fixture(boolean structuredOutput, boolean tools, boolean vision, boolean fileInput) {
        return ModelEntry.builder()
                .provider(Provider.ANTHROPIC)
                .model("test-model")
                .inputCostPerMillionTokens(1.0)
                .outputCostPerMillionTokens(1.0)
                .thinkingScore(5.0)
                .speedScore(5.0)
                .contextWindowTokens(128_000)
                .maxOutputTokens(4_096)
                .supportsStructuredOutput(structuredOutput)
                .supportsTools(tools)
                .supportsVision(vision)
                .supportsFileInput(fileInput)
                .supportsFileOutput(false)
                .lastUpdated(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }
}
