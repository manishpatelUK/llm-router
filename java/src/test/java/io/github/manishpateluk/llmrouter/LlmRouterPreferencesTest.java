package io.github.manishpateluk.llmrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import io.github.manishpateluk.llmrouter.capability.ModelCapabilityTable;
import io.github.manishpateluk.llmrouter.capability.ModelEntry;
import io.github.manishpateluk.llmrouter.capability.ReasoningControl;
import io.github.manishpateluk.llmrouter.config.FallbackPolicy;
import io.github.manishpateluk.llmrouter.config.RouteEntry;
import io.github.manishpateluk.llmrouter.config.RouterConfig;
import io.github.manishpateluk.llmrouter.config.ThinkingLevel;
import io.github.manishpateluk.llmrouter.error.InvalidConfigException;
import io.github.manishpateluk.llmrouter.error.RouterExhaustedException;
import io.github.manishpateluk.llmrouter.model.AttemptOutcome;
import io.github.manishpateluk.llmrouter.model.AttemptRecord;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.provider.Provider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** {@code FallbackPolicy}, whole-request cost estimates, context windows and reasoning — §5.1.2, §5.3, §7.4. */
class LlmRouterPreferencesTest {

    private static final RouteEntry ANTHROPIC = RouteEntry.of(Provider.ANTHROPIC);

    @AfterEach
    void removeFixtures() {
        for (ModelEntry entry : ModelCapabilityTable.listModels(Provider.HUGGINGFACE)) {
            ModelCapabilityTable.removeModel(Provider.HUGGINGFACE, entry.getModel());
        }
    }

    private static RouterConfig.RouterConfigBuilder anthropicAt(ThinkingLevel level) {
        return RouterConfig.builder().route(List.of(ANTHROPIC)).thinkingLevel(level);
    }

    // ---- FallbackPolicy ----

    @Test
    void sameDepthIsTheDefaultAndExhaustsAtTheRequestedLevel() {
        RecordingAdapter anthropic = new RecordingAdapter(Provider.ANTHROPIC, "claude-sonnet-5-5");
        LlmRouter router = new LlmRouter(List.of(anthropic));

        assertThatThrownBy(() -> router.complete("hi", anthropicAt(ThinkingLevel.HIGH).build()))
                .isInstanceOf(RouterExhaustedException.class);
        assertThat(anthropic.models()).containsExactly("claude-opus-5-5");
    }

    @Test
    void finishStepsDownALevelAndReportsWhichLevelAnswered() {
        RecordingAdapter anthropic = new RecordingAdapter(Provider.ANTHROPIC, "claude-sonnet-5-5");
        LlmRouter router = new LlmRouter(List.of(anthropic));

        Response response = router.complete("hi", anthropicAt(ThinkingLevel.HIGH).fallback(FallbackPolicy.FINISH).build());

        assertThat(response.getModelUsed()).isEqualTo("claude-sonnet-5-5");
        assertThat(response.getThinkingLevel()).isEqualTo(ThinkingLevel.MEDIUM);
        assertThat(response.getAttempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.getModel()).isEqualTo("claude-opus-5-5");
            assertThat(attempt.getOutcome()).isEqualTo(AttemptOutcome.FAILED);
            assertThat(attempt.getThinkingLevel()).isEqualTo(ThinkingLevel.HIGH);
        });
    }

    @Test
    void finishWalksDownToLowNeverRetriesAModelAndThenExhausts() {
        RecordingAdapter anthropic = new RecordingAdapter(Provider.ANTHROPIC);
        LlmRouter router = new LlmRouter(List.of(anthropic));

        RouterExhaustedException exhausted = (RouterExhaustedException) org.assertj.core.api.Assertions.catchThrowable(
                () -> router.complete("hi", anthropicAt(ThinkingLevel.MAX).fallback(FallbackPolicy.FINISH).build()));

        // MAX -> fable, HIGH -> opus, MEDIUM -> sonnet, LOW -> sonnet again (closest to the LOW target), not retried
        assertThat(anthropic.models()).containsExactly("claude-fable-5-1", "claude-opus-5-5", "claude-sonnet-5-5");
        assertThat(exhausted.attempts()).extracting(AttemptRecord::getThinkingLevel)
                .containsExactly(ThinkingLevel.MAX, ThinkingLevel.HIGH, ThinkingLevel.MEDIUM);
    }

    @Test
    void finishNeverStepsUp() {
        RecordingAdapter anthropic = new RecordingAdapter(Provider.ANTHROPIC);
        LlmRouter router = new LlmRouter(List.of(anthropic));

        assertThatThrownBy(() -> router.complete("hi", anthropicAt(ThinkingLevel.LOW).fallback(FallbackPolicy.FINISH).build()))
                .isInstanceOf(RouterExhaustedException.class);
        assertThat(anthropic.models()).containsExactly("claude-sonnet-5-5");
    }

    @Test
    void finishTriesFullySpecifiedEntriesOnceAndStepsDownOnlyProviderOnlyEntries() {
        RecordingAdapter anthropic = new RecordingAdapter(Provider.ANTHROPIC);
        RecordingAdapter openai = new RecordingAdapter(Provider.OPENAI, "gpt-6-luna");
        LlmRouter router = new LlmRouter(List.of(anthropic, openai));
        RouterConfig config = RouterConfig.builder()
                .route(List.of(RouteEntry.of(Provider.ANTHROPIC, "claude-opus-5-5"), RouteEntry.of(Provider.OPENAI)))
                .thinkingLevel(ThinkingLevel.HIGH)
                .fallback(FallbackPolicy.FINISH)
                .build();

        Response response = router.complete("hi", config);

        assertThat(anthropic.models()).containsExactly("claude-opus-5-5");
        // openai scores 9.5 / 8 / 5: HIGH -> sol (8), MEDIUM -> sol again (skipped), LOW -> luna
        assertThat(openai.models()).containsExactly("gpt-6.1-sol", "gpt-6-luna");
        assertThat(response.getModelUsed()).isEqualTo("gpt-6-luna");
        assertThat(response.getThinkingLevel()).isEqualTo(ThinkingLevel.LOW);
    }

    @Test
    void finishAppliesToTheDefaultRouteAndTheAsyncPathToo() {
        RecordingAdapter anthropic = new RecordingAdapter(Provider.ANTHROPIC, "claude-sonnet-5-5");
        LlmRouter router = new LlmRouter(List.of(anthropic));
        RouterConfig config = RouterConfig.builder().thinkingLevel(ThinkingLevel.HIGH).fallback(FallbackPolicy.FINISH).build();

        Response response = router.completeAsync(Request.builder().prompt("hi").config(config).build()).join();

        assertThat(response.getModelUsed()).isEqualTo("claude-sonnet-5-5");
        assertThat(response.getThinkingLevel()).isEqualTo(ThinkingLevel.MEDIUM);
    }

    @Test
    void theAnsweringLevelIsReportedWithoutFallbackToo() {
        RecordingAdapter anthropic = new RecordingAdapter(Provider.ANTHROPIC, "claude-opus-5-5");
        Response response = new LlmRouter(List.of(anthropic)).complete("hi", anthropicAt(ThinkingLevel.HIGH).build());

        assertThat(response.getThinkingLevel()).isEqualTo(ThinkingLevel.HIGH);
        assertThat(response.getAttempts()).isEmpty();
    }

    @Test
    void anExplicitlyNulledFallbackIsAConfigError() {
        LlmRouter router = new LlmRouter(List.of(new RecordingAdapter(Provider.ANTHROPIC)));

        assertThatThrownBy(() -> router.complete("hi", RouterConfig.builder().fallback(null).build()))
                .isInstanceOf(InvalidConfigException.class)
                .hasMessageContaining("fallback");
    }

    // ---- Cost and context ----

    private static void registerHuggingFace(String model, double input, double output, int contextWindow) {
        ModelCapabilityTable.registerModel(ModelEntry.builder()
                .provider(Provider.HUGGINGFACE).model(model)
                .inputCostPerMillionTokens(input).outputCostPerMillionTokens(output)
                .thinkingScore(5).speedScore(5)
                .contextWindowTokens(contextWindow).maxOutputTokens(8_000)
                .inputMediaTypes(List.of())
                .lastUpdated(Instant.parse("2026-10-15T00:00:00Z"))
                .build());
    }

    @Test
    void costOptimizedRanksOnTheWholeRequestIncludingExpectedOutput() {
        registerHuggingFace("cheap-input-dear-output", 0.10, 100.0, 100_000);
        registerHuggingFace("balanced", 0.50, 0.50, 100_000);
        RecordingAdapter huggingFace = new RecordingAdapter(Provider.HUGGINGFACE);
        LlmRouter router = new LlmRouter(List.of(huggingFace));
        RouterConfig config = RouterConfig.builder().route(List.of(RouteEntry.of(Provider.HUGGINGFACE))).costOptimized(true).build();

        assertThatThrownBy(() -> router.complete("a short question", config)).isInstanceOf(RouterExhaustedException.class);

        // Prompt-only input cost would put cheap-input-dear-output first; its 1,000 expected output tokens make it dearer.
        assertThat(huggingFace.models()).containsExactly("balanced", "cheap-input-dear-output");
    }

    @Test
    void costOptimizedCountsHistoryAndSystemPromptNotJustThePrompt() {
        registerHuggingFace("cheap-input", 0.10, 2.0, 1_000_000);
        registerHuggingFace("cheap-output", 1.00, 1.0, 1_000_000);
        RecordingAdapter huggingFace = new RecordingAdapter(Provider.HUGGINGFACE);
        LlmRouter router = new LlmRouter(List.of(huggingFace));
        RouterConfig config = RouterConfig.builder().route(List.of(RouteEntry.of(Provider.HUGGINGFACE))).costOptimized(true).build();

        // Short prompt: output dominates, so cheap-output (1.0 + 1.0) beats cheap-input (0.1 + 2.0) per 1,000 tokens...
        assertThatThrownBy(() -> router.complete("hi", config)).isInstanceOf(RouterExhaustedException.class);
        assertThat(huggingFace.models().get(0)).isEqualTo("cheap-output");

        // ...but 200,000 tokens of system prompt make input dominate.
        huggingFace.calls().clear();
        Request longContext = Request.builder().prompt("hi").systemInstructions("x".repeat(800_000)).config(config).build();
        assertThatThrownBy(() -> router.complete(longContext)).isInstanceOf(RouterExhaustedException.class);
        assertThat(huggingFace.models().get(0)).isEqualTo("cheap-input");
    }

    @Test
    void aFullySpecifiedModelWhoseContextWindowCantHoldTheRequestIsSkipped() {
        registerHuggingFace("small-context", 1, 1, 1_000);
        RecordingAdapter huggingFace = new RecordingAdapter(Provider.HUGGINGFACE, "small-context");
        RecordingAdapter anthropic = new RecordingAdapter(Provider.ANTHROPIC, "claude-opus-5-5");
        LlmRouter router = new LlmRouter(List.of(huggingFace, anthropic));
        RouterConfig config = RouterConfig.builder()
                .route(List.of(RouteEntry.of(Provider.HUGGINGFACE, "small-context"), RouteEntry.of(Provider.ANTHROPIC, "claude-opus-5-5")))
                .build();

        Response response = router.complete("x".repeat(8_000), config);

        assertThat(huggingFace.calls()).isEmpty();
        assertThat(response.getModelUsed()).isEqualTo("claude-opus-5-5");
        assertThat(response.getAttempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.getOutcome()).isEqualTo(AttemptOutcome.SKIPPED);
            assertThat(attempt.getReason()).startsWith("estimated request (~2004 tokens) exceeds the context window (1000 tokens)");
        });
    }

    @Test
    void nothingIsSkippedForSizeWhenAnInterceptorCanShrinkTheRequest() {
        registerHuggingFace("small-context", 1, 1, 1_000);
        RecordingAdapter huggingFace = new RecordingAdapter(Provider.HUGGINGFACE, "small-context");
        RequestInterceptor compressor = (provider, model, request) -> request.toBuilder().prompt("x".repeat(400)).build();
        LlmRouter router = new LlmRouter(List.of(huggingFace), compressor);
        RouterConfig config = RouterConfig.builder().route(List.of(RouteEntry.of(Provider.HUGGINGFACE, "small-context"))).build();

        Response response = router.complete("x".repeat(8_000), config);

        assertThat(response.getModelUsed()).isEqualTo("small-context");
        assertThat(huggingFace.calls().get(0).request().getPrompt()).hasSize(400);
    }

    @Test
    void providerOnlyEntriesExpandOnlyOverModelsWhoseContextWindowFits() {
        registerHuggingFace("small-context", 0.01, 0.01, 1_000);
        registerHuggingFace("large-context", 5, 5, 100_000);
        RecordingAdapter huggingFace = new RecordingAdapter(Provider.HUGGINGFACE, "large-context");
        LlmRouter router = new LlmRouter(List.of(huggingFace));
        RouterConfig config = RouterConfig.builder().route(List.of(RouteEntry.of(Provider.HUGGINGFACE))).costOptimized(true).build();

        assertThat(router.complete("x".repeat(8_000), config).getModelUsed()).isEqualTo("large-context");
        assertThat(huggingFace.models()).containsExactly("large-context");
    }

    @Test
    void aProviderWithNoModelLargeEnoughIsSkippedWithTheReason() {
        registerHuggingFace("small-context", 1, 1, 1_000);
        LlmRouter router = new LlmRouter(List.of(new RecordingAdapter(Provider.HUGGINGFACE)));
        RouterConfig config = RouterConfig.builder().route(List.of(RouteEntry.of(Provider.HUGGINGFACE))).build();

        RouterExhaustedException exhausted = (RouterExhaustedException) org.assertj.core.api.Assertions.catchThrowable(
                () -> router.complete("x".repeat(8_000), config));

        assertThat(exhausted.attempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.getModel()).isNull();
            assertThat(attempt.getReason()).isEqualTo("no huggingface model qualifies: "
                    + "estimated request (~2004 tokens) exceeds the context window (1000 tokens)");
        });
    }

    // ---- Reasoning ----

    @Test
    void thinkingLevelReachesTheProviderOnlyWhenAskedAndIsReported() {
        RecordingAdapter anthropic = new RecordingAdapter(Provider.ANTHROPIC, "claude-opus-5-5");
        LlmRouter router = new LlmRouter(List.of(anthropic));
        RouterConfig config = RouterConfig.builder()
                .route(List.of(RouteEntry.of(Provider.ANTHROPIC, "claude-opus-5-5")))
                .thinkingLevel(ThinkingLevel.MAX)
                .build();

        Response unchanged = router.complete("hi", config);
        Response sent = router.complete("hi", config.toBuilder().sendThinkingLevel(true).build());

        assertThat(unchanged.getReasoning()).isNull();
        assertThat(anthropic.calls().get(0).request().getReasoningEffort()).isNull();
        assertThat(sent.getReasoning().getControl()).isEqualTo(ReasoningControl.EFFORT);
        assertThat(sent.getReasoning().getEffort()).isEqualTo("max");
        assertThat(anthropic.calls().get(1).request().getReasoningEffort()).isEqualTo("max");
    }

    @Test
    void aSteppedDownCandidateIsSentItsOwnLowerLevel() {
        RecordingAdapter anthropic = new RecordingAdapter(Provider.ANTHROPIC, "claude-sonnet-5-5");
        LlmRouter router = new LlmRouter(List.of(anthropic));

        Response response = router.complete("hi", anthropicAt(ThinkingLevel.HIGH)
                .fallback(FallbackPolicy.FINISH).sendThinkingLevel(true).build());

        assertThat(anthropic.calls()).extracting(call -> call.request().getReasoningEffort()).containsExactly("high", "medium");
        assertThat(response.getReasoning().getEffort()).isEqualTo("medium");
    }

    @Test
    void aBudgetModelGetsABudgetAndItsSamplingIsDroppedAndReported() {
        // No seeded model takes a thinking budget any more, so register one shaped like Claude Haiku 4.5.
        ModelCapabilityTable.registerModel(ModelEntry.builder()
                .provider(Provider.HUGGINGFACE).model("budget-model")
                .inputCostPerMillionTokens(1).outputCostPerMillionTokens(5)
                .thinkingScore(6).speedScore(10)
                .contextWindowTokens(200_000).maxOutputTokens(64_000)
                .inputMediaTypes(List.of())
                .supportsTemperature(true).supportsTopP(true)
                .reasoningControl(ReasoningControl.BUDGET)
                .lastUpdated(Instant.parse("2026-10-16T00:00:00Z"))
                .build());
        RecordingAdapter anthropic = new RecordingAdapter(Provider.HUGGINGFACE, "budget-model");
        LlmRouter router = new LlmRouter(List.of(anthropic));
        RouterConfig config = RouterConfig.builder()
                .route(List.of(RouteEntry.of(Provider.HUGGINGFACE, "budget-model")))
                .thinkingLevel(ThinkingLevel.HIGH)
                .sendThinkingLevel(true)
                .temperature(0.2)
                .build();

        Response response = router.complete("hi", config);

        assertThat(response.getReasoning().getBudgetTokens()).isEqualTo(16_384);
        assertThat(anthropic.calls().get(0).request().getReasoningBudgetTokens()).isEqualTo(16_384);
        assertThat(anthropic.calls().get(0).request().getTemperature()).isNull();
        assertThat(response.getDroppedFeatures()).containsExactly("temperature");
    }

    @Test
    void droppedAttachmentsReachTheCaller() {
        RecordingAdapter perplexity = new RecordingAdapter(Provider.NVIDIA, "mistralai/mistral-large-2-instruct");
        LlmRouter router = new LlmRouter(List.of(perplexity));
        Request request = Request.builder()
                .prompt("what's in this?")
                .attachments(List.of(TestMedia.pngAttachment()))
                .config(RouterConfig.builder().route(List.of(RouteEntry.of(Provider.NVIDIA, "mistralai/mistral-large-2-instruct"))).build())
                .build();

        Response response = router.complete(request);

        assertThat(response.getDroppedFeatures()).containsExactly("attachments");
        assertThat(response.getDroppedAttachments()).singleElement().satisfies(dropped -> {
            assertThat(dropped.getFilename()).isEqualTo("chart.png");
            assertThat(dropped.getReason()).isEqualTo("model does not accept image/png");
        });
        assertThat(perplexity.calls().get(0).request().getAttachments()).isEmpty();
    }

    @Test
    void aRequiredFeatureThatNoLowerLevelModelHasStillExhaustsUnderFinish() {
        RecordingAdapter nvidia = new RecordingAdapter(Provider.NVIDIA);
        LlmRouter router = new LlmRouter(List.of(nvidia));
        RouterConfig config = RouterConfig.builder()
                .route(List.of(RouteEntry.of(Provider.NVIDIA)))
                .thinkingLevel(ThinkingLevel.MAX)
                .fallback(FallbackPolicy.FINISH)
                .requiredFeatures(Set.of(io.github.manishpateluk.llmrouter.config.Feature.ATTACHMENTS))
                .build();
        Request request = Request.builder().prompt("p").attachments(List.of(TestMedia.pngAttachment())).config(config).build();

        RouterExhaustedException exhausted = (RouterExhaustedException) org.assertj.core.api.Assertions.catchThrowable(
                () -> router.complete(request));

        assertThat(nvidia.calls()).isEmpty();
        // recorded once, not once per level
        assertThat(exhausted.attempts()).singleElement().extracting(AttemptRecord::getReason)
                .isEqualTo("no nvidia model supports required feature(s) [attachments]");
    }
}
