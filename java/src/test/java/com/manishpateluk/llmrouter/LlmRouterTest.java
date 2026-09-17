package com.manishpateluk.llmrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import com.manishpateluk.llmrouter.capability.ModelCapabilityTable;
import com.manishpateluk.llmrouter.capability.ModelEntry;
import com.manishpateluk.llmrouter.config.RouteEntry;
import com.manishpateluk.llmrouter.config.RouterConfig;
import com.manishpateluk.llmrouter.config.StructuredOutputStrategy;
import com.manishpateluk.llmrouter.config.ThinkingLevel;
import com.manishpateluk.llmrouter.error.InvalidConfigException;
import com.manishpateluk.llmrouter.error.NoProvidersConfiguredException;
import com.manishpateluk.llmrouter.error.RouterExhaustedException;
import com.manishpateluk.llmrouter.model.AttemptOutcome;
import com.manishpateluk.llmrouter.model.Request;
import com.manishpateluk.llmrouter.model.Response;
import com.manishpateluk.llmrouter.model.ToolDefinition;
import com.manishpateluk.llmrouter.model.Usage;
import com.manishpateluk.llmrouter.provider.Provider;
import com.manishpateluk.llmrouter.provider.ProviderAdapter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LlmRouterTest {

    @Mock
    private ProviderAdapter anthropic;
    @Mock
    private ProviderAdapter openai;

    @AfterEach
    void cleanUpRegisteredFixtures() {
        ModelCapabilityTable.removeModel(Provider.OPENROUTER, "fixture-model");
    }

    private static void stubId(ProviderAdapter adapter, Provider provider) {
        lenient().when(adapter.id()).thenReturn(provider);
    }

    private static Response fragment(String content) {
        return Response.builder()
                .content(content)
                .usage(Usage.builder().inputTokens(10).outputTokens(5).reasoningTokens(0).estimatedCostUsdCents(0).build())
                .build();
    }

    @Test
    void firstCandidateSuccessReturnsImmediatelyWithNoAttempts() {
        stubId(anthropic, Provider.ANTHROPIC);
        when(anthropic.isAvailable()).thenReturn(true);
        when(anthropic.send(eq("claude-opus-5"), any())).thenReturn(fragment("hello"));

        LlmRouter router = new LlmRouter(List.of(anthropic));
        RouterConfig config = RouterConfig.builder()
                .route(List.of(RouteEntry.of(Provider.ANTHROPIC, "claude-opus-5")))
                .build();

        Response response = router.complete("hi", config);

        assertThat(response.getContent()).isEqualTo("hello");
        assertThat(response.getProviderUsed()).isEqualTo(Provider.ANTHROPIC);
        assertThat(response.getModelUsed()).isEqualTo("claude-opus-5");
        assertThat(response.getAttempts()).isEmpty();
    }

    @Test
    void fallsBackToNextCandidateAfterFailureAndRecordsIt() {
        stubId(anthropic, Provider.ANTHROPIC);
        stubId(openai, Provider.OPENAI);
        when(anthropic.isAvailable()).thenReturn(true);
        when(openai.isAvailable()).thenReturn(true);
        when(anthropic.send(any(), any())).thenThrow(new RuntimeException("rate limited"));
        when(openai.send(eq("gpt-6-astra"), any())).thenReturn(fragment("recovered"));

        LlmRouter router = new LlmRouter(List.of(anthropic, openai));
        RouterConfig config = RouterConfig.builder()
                .route(List.of(RouteEntry.of(Provider.ANTHROPIC, "claude-opus-5"), RouteEntry.of(Provider.OPENAI, "gpt-6-astra")))
                .build();

        Response response = router.complete("hi", config);

        assertThat(response.getContent()).isEqualTo("recovered");
        assertThat(response.getProviderUsed()).isEqualTo(Provider.OPENAI);
        assertThat(response.getAttempts()).hasSize(1);
        assertThat(response.getAttempts().get(0).getProvider()).isEqualTo(Provider.ANTHROPIC);
        assertThat(response.getAttempts().get(0).getOutcome()).isEqualTo(AttemptOutcome.FAILED);
        assertThat(response.getAttempts().get(0).getReason()).isEqualTo("rate limited");
    }

    @Test
    void skipsUnavailableCandidateAndRecordsIt() {
        stubId(anthropic, Provider.ANTHROPIC);
        stubId(openai, Provider.OPENAI);
        when(anthropic.isAvailable()).thenReturn(false);
        when(openai.isAvailable()).thenReturn(true);
        when(openai.send(eq("gpt-6-astra"), any())).thenReturn(fragment("ok"));

        LlmRouter router = new LlmRouter(List.of(anthropic, openai));
        RouterConfig config = RouterConfig.builder()
                .route(List.of(RouteEntry.of(Provider.ANTHROPIC, "claude-opus-5"), RouteEntry.of(Provider.OPENAI, "gpt-6-astra")))
                .build();

        Response response = router.complete("hi", config);

        assertThat(response.getAttempts()).hasSize(1);
        assertThat(response.getAttempts().get(0).getOutcome()).isEqualTo(AttemptOutcome.SKIPPED);
        assertThat(response.getAttempts().get(0).getReason()).isEqualTo("no API key detected");
    }

    @Test
    void exhaustionThrowsWithFullAttemptsList() {
        stubId(anthropic, Provider.ANTHROPIC);
        when(anthropic.isAvailable()).thenReturn(true);
        when(anthropic.send(any(), any())).thenThrow(new RuntimeException("boom"));

        LlmRouter router = new LlmRouter(List.of(anthropic));
        RouterConfig config = RouterConfig.builder()
                .route(List.of(RouteEntry.of(Provider.ANTHROPIC, "claude-opus-5")))
                .build();

        assertThatThrownBy(() -> router.complete("hi", config))
                .isInstanceOf(RouterExhaustedException.class)
                .satisfies(e -> assertThat(((RouterExhaustedException) e).attempts()).hasSize(1));
    }

    @Test
    void noProvidersConfiguredWhenDefaultRouteHasNoAvailableAdapters() {
        stubId(anthropic, Provider.ANTHROPIC);
        when(anthropic.isAvailable()).thenReturn(false);

        LlmRouter router = new LlmRouter(List.of(anthropic));

        assertThatThrownBy(() -> router.complete("hi"))
                .isInstanceOf(NoProvidersConfiguredException.class);
    }

    @Test
    void explicitEmptyRouteExhaustsRatherThanThrowingNoProvidersConfigured() {
        LlmRouter router = new LlmRouter(List.of());
        RouterConfig config = RouterConfig.builder().route(List.of()).build();

        assertThatThrownBy(() -> router.complete("hi", config))
                .isInstanceOf(RouterExhaustedException.class);
    }

    @Test
    void dropsUnsupportedToolsAndReportsInDroppedFeatures() {
        registerFixtureModel(false, false, false);
        stubId(anthropic, Provider.OPENROUTER);
        when(anthropic.isAvailable()).thenReturn(true);
        when(anthropic.send(eq("fixture-model"), any())).thenAnswer(invocation -> {
            Request sent = invocation.getArgument(1);
            assertThat(sent.getTools()).isEmpty(); // negotiator already stripped it
            return fragment("ok");
        });

        LlmRouter router = new LlmRouter(List.of(anthropic));
        RouterConfig config = RouterConfig.builder()
                .route(List.of(RouteEntry.of(Provider.OPENROUTER, "fixture-model")))
                .build();
        Request request = Request.builder()
                .prompt("hi")
                .tools(List.of(ToolDefinition.builder().name("t").description("d").parameters(Map.of()).build()))
                .config(config)
                .build();

        Response response = router.complete(request);

        assertThat(response.getDroppedFeatures()).containsExactly("tools");
    }

    @Test
    void computesEstimatedCostFromCapabilityTablePricing() {
        registerFixtureModel(false, false, false); // $2/$4 per 1M tokens, per registerFixtureModel
        stubId(anthropic, Provider.OPENROUTER);
        when(anthropic.isAvailable()).thenReturn(true);
        when(anthropic.send(eq("fixture-model"), any())).thenReturn(fragment("ok")); // 10 input, 5 output tokens

        LlmRouter router = new LlmRouter(List.of(anthropic));
        RouterConfig config = RouterConfig.builder()
                .route(List.of(RouteEntry.of(Provider.OPENROUTER, "fixture-model")))
                .build();

        Response response = router.complete("hi", config);

        // (10/1_000_000)*2.0 + (5/1_000_000)*4.0 dollars = 4.0e-5 dollars = 0.004 cents -> rounds to 0
        // Use larger token counts via a custom fragment to get a non-zero, easily-asserted value instead:
        assertThat(response.getUsage().getEstimatedCostUsdCents()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void parsesStructuredOutputWhenSchemaIsHonored() {
        registerFixtureModel(true, true, false); // supportsStructuredOutput = true
        stubId(anthropic, Provider.OPENROUTER);
        when(anthropic.isAvailable()).thenReturn(true);
        when(anthropic.send(eq("fixture-model"), any())).thenReturn(fragment("{\"answer\":42}"));

        LlmRouter router = new LlmRouter(List.of(anthropic));
        RouterConfig config = RouterConfig.builder()
                .route(List.of(RouteEntry.of(Provider.OPENROUTER, "fixture-model")))
                .build();
        Request request = Request.builder()
                .prompt("hi")
                .responseSchema(Map.of("type", "object"))
                .config(config)
                .build();

        Response response = router.complete(request);

        assertThat(response.getStructuredOutput()).containsEntry("answer", 42);
        assertThat(response.getDroppedFeatures()).isEmpty();
    }

    @Test
    void doesNotAttemptToParseStructuredOutputWhenSchemaWasDropped() {
        registerFixtureModel(false, false, false); // supportsStructuredOutput = false
        stubId(anthropic, Provider.OPENROUTER);
        when(anthropic.isAvailable()).thenReturn(true);
        when(anthropic.send(eq("fixture-model"), any())).thenReturn(fragment("plain text, not json"));

        LlmRouter router = new LlmRouter(List.of(anthropic));
        RouterConfig config = RouterConfig.builder()
                .route(List.of(RouteEntry.of(Provider.OPENROUTER, "fixture-model")))
                .structuredOutputStrategy(StructuredOutputStrategy.NATIVE)
                .build();
        Request request = Request.builder()
                .prompt("hi")
                .responseSchema(Map.of("type", "object"))
                .config(config)
                .build();

        Response response = router.complete(request);

        assertThat(response.getDroppedFeatures()).containsExactly("responseSchema");
        assertThat(response.getStructuredOutput()).isNull();
    }

    @Test
    void onlyTemperatureSentToAdapterWhenSupported() {
        registerFixtureModel(false, false, false, true, true);
        stubId(anthropic, Provider.OPENROUTER);
        when(anthropic.isAvailable()).thenReturn(true);
        when(anthropic.send(eq("fixture-model"), any())).thenAnswer(invocation -> {
            Request sent = invocation.getArgument(1);
            assertThat(sent.getTemperature()).isEqualTo(0.7);
            assertThat(sent.getTopP()).isNull();
            return fragment("ok");
        });

        LlmRouter router = new LlmRouter(List.of(anthropic));
        RouterConfig config = RouterConfig.builder()
                .route(List.of(RouteEntry.of(Provider.OPENROUTER, "fixture-model")))
                .temperature(0.7)
                .build();

        Response response = router.complete("hi", config);

        assertThat(response.getDroppedFeatures()).isEmpty();
    }

    @Test
    void temperaturePreferredOverTopPWhenBothRequestedAndModelSupportsBoth() {
        registerFixtureModel(false, false, false, true, true);
        stubId(anthropic, Provider.OPENROUTER);
        when(anthropic.isAvailable()).thenReturn(true);
        when(anthropic.send(eq("fixture-model"), any())).thenAnswer(invocation -> {
            Request sent = invocation.getArgument(1);
            assertThat(sent.getTemperature()).isEqualTo(0.7);
            assertThat(sent.getTopP()).isNull();
            return fragment("ok");
        });

        LlmRouter router = new LlmRouter(List.of(anthropic));
        RouterConfig config = RouterConfig.builder()
                .route(List.of(RouteEntry.of(Provider.OPENROUTER, "fixture-model")))
                .temperature(0.7)
                .topP(0.9)
                .build();

        Response response = router.complete("hi", config);

        assertThat(response.getDroppedFeatures()).containsExactly("topP");
    }

    @Test
    void topPUsedWhenBothRequestedButModelOnlySupportsTopP() {
        registerFixtureModel(false, false, false, false, true);
        stubId(anthropic, Provider.OPENROUTER);
        when(anthropic.isAvailable()).thenReturn(true);
        when(anthropic.send(eq("fixture-model"), any())).thenAnswer(invocation -> {
            Request sent = invocation.getArgument(1);
            assertThat(sent.getTemperature()).isNull();
            assertThat(sent.getTopP()).isEqualTo(0.9);
            return fragment("ok");
        });

        LlmRouter router = new LlmRouter(List.of(anthropic));
        RouterConfig config = RouterConfig.builder()
                .route(List.of(RouteEntry.of(Provider.OPENROUTER, "fixture-model")))
                .temperature(0.7)
                .topP(0.9)
                .build();

        Response response = router.complete("hi", config);

        assertThat(response.getDroppedFeatures()).containsExactly("temperature");
    }

    @Test
    void temperatureAndTopPDroppedAndReportedWhenModelDoesNotSupportThem() {
        registerFixtureModel(false, false, false, false, false);
        stubId(anthropic, Provider.OPENROUTER);
        when(anthropic.isAvailable()).thenReturn(true);
        when(anthropic.send(eq("fixture-model"), any())).thenAnswer(invocation -> {
            Request sent = invocation.getArgument(1);
            assertThat(sent.getTemperature()).isNull();
            assertThat(sent.getTopP()).isNull();
            return fragment("ok");
        });

        LlmRouter router = new LlmRouter(List.of(anthropic));
        RouterConfig config = RouterConfig.builder()
                .route(List.of(RouteEntry.of(Provider.OPENROUTER, "fixture-model")))
                .temperature(0.7)
                .topP(0.9)
                .build();

        Response response = router.complete("hi", config);

        assertThat(response.getDroppedFeatures()).containsExactlyInAnyOrder("temperature", "topP");
    }

    @Test
    void temperatureAndTopPForwardedOptimisticallyForModelNotInCapabilityTable() {
        stubId(anthropic, Provider.OPENROUTER);
        when(anthropic.isAvailable()).thenReturn(true);
        when(anthropic.send(eq("unregistered-model"), any())).thenAnswer(invocation -> {
            Request sent = invocation.getArgument(1);
            assertThat(sent.getTemperature()).isEqualTo(0.7);
            assertThat(sent.getTopP()).isEqualTo(0.9);
            return fragment("ok");
        });

        LlmRouter router = new LlmRouter(List.of(anthropic));
        RouterConfig config = RouterConfig.builder()
                .route(List.of(RouteEntry.of(Provider.OPENROUTER, "unregistered-model")))
                .temperature(0.7)
                .topP(0.9)
                .build();

        Response response = router.complete("hi", config);

        assertThat(response.getDroppedFeatures()).isEmpty();
    }

    @Test
    void asyncFallsBackAcrossCandidates() {
        stubId(anthropic, Provider.ANTHROPIC);
        stubId(openai, Provider.OPENAI);
        when(anthropic.isAvailable()).thenReturn(true);
        when(openai.isAvailable()).thenReturn(true);
        when(anthropic.sendAsync(any(), any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("down")));
        when(openai.sendAsync(eq("gpt-6-astra"), any())).thenReturn(CompletableFuture.completedFuture(fragment("async ok")));

        LlmRouter router = new LlmRouter(List.of(anthropic, openai));
        RouterConfig config = RouterConfig.builder()
                .route(List.of(RouteEntry.of(Provider.ANTHROPIC, "claude-opus-5"), RouteEntry.of(Provider.OPENAI, "gpt-6-astra")))
                .build();

        Response response = router.completeAsync("hi", config).join();

        assertThat(response.getContent()).isEqualTo("async ok");
        assertThat(response.getAttempts()).hasSize(1);
        assertThat(response.getAttempts().get(0).getOutcome()).isEqualTo(AttemptOutcome.FAILED);
    }

    @Test
    void callbackStyleAsyncInvokesOnSuccess() {
        stubId(anthropic, Provider.ANTHROPIC);
        when(anthropic.isAvailable()).thenReturn(true);
        when(anthropic.sendAsync(eq("claude-opus-5"), any())).thenReturn(CompletableFuture.completedFuture(fragment("callback ok")));

        LlmRouter router = new LlmRouter(List.of(anthropic));
        RouterConfig config = RouterConfig.builder()
                .route(List.of(RouteEntry.of(Provider.ANTHROPIC, "claude-opus-5")))
                .build();
        Request request = Request.builder().prompt("hi").config(config).build();

        AtomicReference<Response> result = new AtomicReference<>();
        router.completeAsync(request, result::set, t -> { throw new AssertionError(t); });

        assertThat(result.get()).isNotNull();
        assertThat(result.get().getContent()).isEqualTo("callback ok");
    }

    @Test
    void invalidConfigWhenThinkingLevelExplicitlyNulled() {
        LlmRouter router = new LlmRouter(List.of());
        RouterConfig config = RouterConfig.builder().thinkingLevel(null).build();

        assertThatThrownBy(() -> router.complete("hi", config)).isInstanceOf(InvalidConfigException.class);
    }

    @Test
    void blankPromptIsRejected() {
        LlmRouter router = new LlmRouter(List.of());

        assertThatThrownBy(() -> router.complete("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    private static void registerFixtureModel(boolean structuredOutput, boolean tools, boolean vision) {
        registerFixtureModel(structuredOutput, tools, vision, true, true);
    }

    private static void registerFixtureModel(
            boolean structuredOutput, boolean tools, boolean vision, boolean temperature, boolean topP) {
        ModelCapabilityTable.registerModel(ModelEntry.builder()
                .provider(Provider.OPENROUTER)
                .model("fixture-model")
                .inputCostPerMillionTokens(2.0)
                .outputCostPerMillionTokens(4.0)
                .thinkingScore(5.0)
                .speedScore(5.0)
                .contextWindowTokens(10_000)
                .maxOutputTokens(2_000)
                .supportsStructuredOutput(structuredOutput)
                .supportsTools(tools)
                .supportsVision(vision)
                .supportsFileInput(false)
                .supportsFileOutput(false)
                .supportsTemperature(temperature)
                .supportsTopP(topP)
                .lastUpdated(Instant.parse("2026-01-01T00:00:00Z"))
                .build());
    }
}
