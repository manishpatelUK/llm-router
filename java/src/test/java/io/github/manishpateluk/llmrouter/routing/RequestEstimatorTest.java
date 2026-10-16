package io.github.manishpateluk.llmrouter.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;

import io.github.manishpateluk.llmrouter.TestMedia;
import io.github.manishpateluk.llmrouter.capability.ModelCapabilityTable;
import io.github.manishpateluk.llmrouter.capability.ModelEntry;
import io.github.manishpateluk.llmrouter.model.Attachment;
import io.github.manishpateluk.llmrouter.model.Message;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.ToolCall;
import io.github.manishpateluk.llmrouter.model.ToolDefinition;
import io.github.manishpateluk.llmrouter.provider.Provider;

import org.junit.jupiter.api.Test;

class RequestEstimatorTest {

    private static final String FOUR_HUNDRED_CHARS = "x".repeat(400); // ~100 tokens

    private static ModelEntry model(Provider provider, String id) {
        return ModelCapabilityTable.findModel(provider, id).orElseThrow();
    }

    @Test
    void textTokensCountSystemHistoryToolCallsToolsAndSchemaNotJustThePrompt() {
        Request promptOnly = Request.builder().prompt(FOUR_HUNDRED_CHARS).build();
        Request whole = promptOnly.toBuilder()
                .systemInstructions(FOUR_HUNDRED_CHARS)
                .history(List.of(
                        Message.user(FOUR_HUNDRED_CHARS),
                        Message.assistant("", List.of(ToolCall.builder().id("1").name("search")
                                .arguments(Map.of("q", FOUR_HUNDRED_CHARS)).build()))))
                .tools(List.of(ToolDefinition.builder().name("search").description(FOUR_HUNDRED_CHARS)
                        .parameters(Map.of("type", "object")).build()))
                .responseSchema(Map.of("type", "object", "description", FOUR_HUNDRED_CHARS))
                .build();

        int promptTokens = RequestEstimator.textTokens(promptOnly);
        assertThat(promptTokens).isBetween(100, 110);
        assertThat(RequestEstimator.textTokens(whole) - promptTokens).isGreaterThan(500);
    }

    @Test
    void imagesCostEachProvidersDocumentedRate() {
        Attachment png = Attachment.builder().mediaType("image/png").data(TestMedia.png(1000, 1000)).build();

        // Anthropic 4.7+: ceil(1000/28)^2 = 36 * 36
        assertThat(RequestEstimator.attachmentTokens(Provider.ANTHROPIC, "claude-opus-5-5", png)).isEqualTo(1_296);
        // Anthropic before 4.7: the standard tier caps an image at 1,568 tokens
        Attachment large = Attachment.builder().mediaType("image/png").data(TestMedia.png(4000, 4000)).build();
        assertThat(RequestEstimator.attachmentTokens(Provider.ANTHROPIC, "claude-haiku-4-5-20251001", large)).isLessThanOrEqualTo(1_568);
        assertThat(RequestEstimator.attachmentTokens(Provider.ANTHROPIC, "claude-opus-5-5", large)).isBetween(4_000, 4_784);
        // OpenAI: ceil(1000/32)^2 = 32 * 32 patches, x 1.2
        assertThat(RequestEstimator.attachmentTokens(Provider.OPENAI, "gpt-6-astra", png)).isEqualTo(1_229);
        // Gemini 3: a flat 1,120
        assertThat(RequestEstimator.attachmentTokens(Provider.OPENROUTER, "google/gemini-3.8-flash", png)).isEqualTo(1_120);
        // Others (Perplexity's rate): w * h / 750
        assertThat(RequestEstimator.attachmentTokens(Provider.PERPLEXITY, "perplexity/sonar", png)).isEqualTo(1_334);
    }

    @Test
    void pdfsCostPerPage() {
        Attachment threePages = Attachment.builder().mediaType("application/pdf").data(TestMedia.pdf(3)).build();

        assertThat(RequestEstimator.attachmentTokens(Provider.ANTHROPIC, "claude-opus-5-5", threePages)).isEqualTo(3 * 3_800);
        assertThat(RequestEstimator.attachmentTokens(Provider.OPENROUTER, "google/gemini-3.8-flash", threePages)).isEqualTo(3 * 560);
        assertThat(RequestEstimator.attachmentTokens(Provider.PERPLEXITY, "perplexity/sonar", threePages)).isEqualTo(3 * 1_000);
    }

    @Test
    void inputTokensOnlyCountAttachmentsTheModelAccepts() {
        Request request = Request.builder().prompt("look").attachments(List.of(TestMedia.pngAttachment())).build();

        int textOnlyModel = RequestEstimator.inputTokens(request, model(Provider.NVIDIA, "mistralai/mistral-large-2-instruct"));
        int visionModel = RequestEstimator.inputTokens(request, model(Provider.ANTHROPIC, "claude-opus-5-5"));

        assertThat(textOnlyModel).isEqualTo(RequestEstimator.textTokens(request));
        assertThat(visionModel).isGreaterThan(textOnlyModel + 1_000);
    }

    @Test
    void costIncludesExpectedOutputAtTheOutputPrice() {
        ModelEntry opus = model(Provider.ANTHROPIC, "claude-opus-5-5"); // $4 in, $20 out
        Request noCap = Request.builder().prompt("hi").build();
        Request capped = noCap.toBuilder().maxOutputTokens(10_000).build();

        double expectedNoCap = RequestEstimator.inputTokens(noCap, opus) * 4e-6 + RequestEstimator.DEFAULT_EXPECTED_OUTPUT_TOKENS * 20e-6;
        assertThat(RequestEstimator.estimatedCostUsd(noCap, opus)).isCloseTo(expectedNoCap, within(1e-9));
        assertThat(RequestEstimator.expectedOutputTokens(capped, opus)).isEqualTo(10_000);
        assertThat(RequestEstimator.expectedOutputTokens(capped.toBuilder().maxOutputTokens(Integer.MAX_VALUE).build(), opus))
                .isEqualTo(opus.getMaxOutputTokens());
    }
}
