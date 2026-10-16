package io.github.manishpateluk.llmrouter.negotiation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import io.github.manishpateluk.llmrouter.TestMedia;
import io.github.manishpateluk.llmrouter.capability.ModelEntry;
import io.github.manishpateluk.llmrouter.capability.ReasoningControl;
import io.github.manishpateluk.llmrouter.config.Feature;
import io.github.manishpateluk.llmrouter.config.StructuredOutputStrategy;
import io.github.manishpateluk.llmrouter.config.ThinkingLevel;
import io.github.manishpateluk.llmrouter.model.Attachment;
import io.github.manishpateluk.llmrouter.model.ReasoningSettings;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.provider.Provider;

import org.junit.jupiter.api.Test;

class ReasoningAndAttachmentLimitsNegotiationTest {

    private static final Request PLAIN = Request.builder().prompt("p").build();

    private static ModelEntry effortModel(String... levels) {
        return ModelEntry.builder().provider(Provider.OPENROUTER).model("m").maxOutputTokens(64_000)
                .reasoningControl(ReasoningControl.EFFORT).reasoningEffortLevels(List.of(levels))
                .supportsTemperature(true).supportsTopP(true).build();
    }

    private static ModelEntry budgetModel(int maxOutputTokens) {
        return ModelEntry.builder().provider(Provider.ANTHROPIC).model("m").maxOutputTokens(maxOutputTokens)
                .reasoningControl(ReasoningControl.BUDGET).supportsTemperature(true).supportsTopP(true).build();
    }

    private static NegotiationResult negotiate(ModelEntry model, Request request, ThinkingLevel level) {
        return CapabilityNegotiator.negotiate(model, request, StructuredOutputStrategy.AUTO, null, null, true, level);
    }

    private static String effort(ModelEntry model, ThinkingLevel level) {
        ReasoningSettings reasoning = CapabilityNegotiator.reasoningFor(model, PLAIN, level);
        return reasoning == null ? null : reasoning.getEffort();
    }

    @Test
    void effortUsesTheSameNamedLevelAndMaxIsAlwaysTheHighest() {
        ModelEntry anthropicLike = effortModel("low", "medium", "high", "xhigh", "max");
        assertThat(effort(anthropicLike, ThinkingLevel.LOW)).isEqualTo("low");
        assertThat(effort(anthropicLike, ThinkingLevel.MEDIUM)).isEqualTo("medium");
        assertThat(effort(anthropicLike, ThinkingLevel.HIGH)).isEqualTo("high");
        assertThat(effort(anthropicLike, ThinkingLevel.MAX)).isEqualTo("max");

        ModelEntry geminiLike = effortModel("low", "medium", "high");
        assertThat(effort(geminiLike, ThinkingLevel.MAX)).isEqualTo("high");

        ModelEntry deepSeekLike = effortModel("low", "high", "max");
        assertThat(effort(deepSeekLike, ThinkingLevel.MEDIUM)).isEqualTo("high");
        assertThat(effort(deepSeekLike, ThinkingLevel.MAX)).isEqualTo("max");
    }

    @Test
    void negotiatedEffortIsOnTheAdaptedRequestAndReported() {
        NegotiationResult result = negotiate(effortModel("low", "medium", "high"), PLAIN, ThinkingLevel.HIGH);

        assertThat(result.getAdaptedRequest().getReasoningEffort()).isEqualTo("high");
        assertThat(result.getAdaptedRequest().getReasoningBudgetTokens()).isNull();
        assertThat(result.getReasoning().getControl()).isEqualTo(ReasoningControl.EFFORT);
    }

    @Test
    void noLevelOrNoControlSendsNothing() {
        assertThat(negotiate(effortModel("low", "high"), PLAIN, null).getReasoning()).isNull();
        ModelEntry none = ModelEntry.builder().provider(Provider.NVIDIA).model("m").build();
        NegotiationResult result = negotiate(none, PLAIN, ThinkingLevel.MAX);
        assertThat(result.getReasoning()).isNull();
        assertThat(result.getAdaptedRequest().getReasoningEffort()).isNull();
    }

    @Test
    void budgetsScaleWithTheLevelAndLowLeavesThinkingOff() {
        ModelEntry haikuLike = budgetModel(64_000);

        assertThat(CapabilityNegotiator.reasoningFor(haikuLike, PLAIN, ThinkingLevel.LOW)).isNull();
        assertThat(CapabilityNegotiator.reasoningFor(haikuLike, PLAIN, ThinkingLevel.MEDIUM).getBudgetTokens()).isEqualTo(4_096);
        assertThat(CapabilityNegotiator.reasoningFor(haikuLike, PLAIN, ThinkingLevel.HIGH).getBudgetTokens()).isEqualTo(16_384);
        assertThat(CapabilityNegotiator.reasoningFor(haikuLike, PLAIN, ThinkingLevel.MAX).getBudgetTokens()).isEqualTo(32_000);
    }

    @Test
    void aBudgetLeavesHalfTheOutputLimitForTheAnswerAndIsDroppedBelowTheMinimum() {
        ModelEntry haikuLike = budgetModel(64_000);
        Request capped = PLAIN.toBuilder().maxOutputTokens(6_000).build();
        Request tiny = PLAIN.toBuilder().maxOutputTokens(2_000).build();

        assertThat(CapabilityNegotiator.reasoningFor(haikuLike, capped, ThinkingLevel.HIGH).getBudgetTokens()).isEqualTo(3_000);
        assertThat(CapabilityNegotiator.reasoningFor(haikuLike, tiny, ThinkingLevel.HIGH)).isNull(); // 1,000 < 1,024
    }

    @Test
    void aThinkingBudgetDropsTemperatureAndTopP() {
        NegotiationResult withBudget = CapabilityNegotiator.negotiate(budgetModel(64_000), PLAIN, StructuredOutputStrategy.AUTO,
                0.2, 0.9, true, ThinkingLevel.MEDIUM);
        assertThat(withBudget.getAdaptedRequest().getReasoningBudgetTokens()).isEqualTo(4_096);
        assertThat(withBudget.getAdaptedRequest().getTemperature()).isNull();
        assertThat(withBudget.getAdaptedRequest().getTopP()).isNull();
        assertThat(withBudget.getDroppedFeatures()).containsExactly("temperature", "topP");

        NegotiationResult lowLevel = CapabilityNegotiator.negotiate(budgetModel(64_000), PLAIN, StructuredOutputStrategy.AUTO,
                0.2, null, true, ThinkingLevel.LOW);
        assertThat(lowLevel.getAdaptedRequest().getTemperature()).isEqualTo(0.2); // no thinking, so sampling stays
    }

    @Test
    void imagesBeyondTheModelsPerRequestLimitAreDropped() {
        ModelEntry model = ModelEntry.builder().provider(Provider.OPENROUTER).model("m")
                .inputMediaTypes(List.of("image/png")).maxImagesPerRequest(2).build();
        Attachment png = TestMedia.pngAttachment();
        Request request = PLAIN.toBuilder().attachments(List.of(png, png, png)).build();

        NegotiationResult result = negotiate(model, request, null);

        assertThat(result.getAdaptedRequest().getAttachments()).hasSize(2);
        assertThat(result.getDroppedAttachments()).singleElement().satisfies(dropped -> {
            assertThat(dropped.getIndex()).isEqualTo(2);
            assertThat(dropped.getReason()).isEqualTo("over the model's limit of 2 images per request");
        });
    }

    @Test
    void pdfsOverThePageOrByteLimitAreDroppedAndRequiringAttachmentsMakesThatUnmet() {
        ModelEntry model = ModelEntry.builder().provider(Provider.ANTHROPIC).model("m")
                .inputMediaTypes(List.of("application/pdf")).maxPdfPages(5).maxPdfBytes(1_000_000).build();
        Request tooManyPages = PLAIN.toBuilder()
                .attachments(List.of(Attachment.builder().mediaType("application/pdf").data(TestMedia.pdf(6)).build())).build();
        Request fits = PLAIN.toBuilder().attachments(List.of(TestMedia.deckPagePdf())).build();
        Request tooBig = PLAIN.toBuilder()
                .attachments(List.of(Attachment.builder().mediaType("application/pdf").data(new byte[1_000_001]).build())).build();

        assertThat(negotiate(model, tooManyPages, null).getDroppedAttachments()).singleElement()
                .extracting("reason").isEqualTo("PDF has 6 pages, over the model's limit of 5");
        assertThat(negotiate(model, tooBig, null).getDroppedAttachments()).singleElement()
                .extracting("reason").isEqualTo("PDF is 1000001 bytes, over the model's limit of 1000000");
        assertThat(negotiate(model, fits, null).getDroppedAttachments()).isEmpty();

        assertThat(CapabilityNegotiator.unmetRequiredFeatures(model, tooManyPages, StructuredOutputStrategy.AUTO,
                Set.of(Feature.ATTACHMENTS))).containsExactly(Feature.ATTACHMENTS);
        assertThat(CapabilityNegotiator.unmetRequiredFeatures(model, fits, StructuredOutputStrategy.AUTO,
                Set.of(Feature.ATTACHMENTS))).isEmpty();
    }
}
