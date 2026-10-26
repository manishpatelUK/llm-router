package io.github.manishpateluk.llmrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import io.github.manishpateluk.llmrouter.capability.ModelCapabilityTable;
import io.github.manishpateluk.llmrouter.capability.ModelEntry;
import io.github.manishpateluk.llmrouter.config.RouteEntry;
import io.github.manishpateluk.llmrouter.config.RouterConfig;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.model.Usage;
import io.github.manishpateluk.llmrouter.provider.Provider;
import io.github.manishpateluk.llmrouter.provider.ProviderAdapter;

import org.junit.jupiter.api.Test;

/** Cached input tokens are priced at the cache rates (§7.1) in {@code Usage.estimatedCostUsd*}. */
class CacheCostTest {

    private static ModelEntry entry(Provider provider, Double cacheRead, Double cacheWrite) {
        return ModelEntry.builder()
                .provider(provider)
                .model("priced")
                .inputCostPerMillionTokens(10)
                .outputCostPerMillionTokens(50)
                .cacheReadCostPerMillionTokens(cacheRead)
                .cacheWriteCostPerMillionTokens(cacheWrite)
                .build();
    }

    private static Usage usage(int input, int cacheRead, int cacheWrite, int output) {
        return Usage.builder().inputTokens(input).cacheReadTokens(cacheRead).cacheWriteTokens(cacheWrite).outputTokens(output).build();
    }

    @Test
    void anthropicDefaultsToATenthForReadsAndOneAndAQuarterForWrites() {
        ModelEntry anthropic = entry(Provider.ANTHROPIC, null, null);

        assertThat(anthropic.effectiveCacheReadCostPerMillionTokens()).isCloseTo(1.0, within(1e-9));
        assertThat(anthropic.effectiveCacheWriteCostPerMillionTokens()).isCloseTo(12.5, within(1e-9));
        // 1M uncached at $10 + 1M read at $1 + 1M written at $12.50 + 1M output at $50
        assertThat(LlmRouter.estimateCostUsd(anthropic, usage(3_000_000, 1_000_000, 1_000_000, 1_000_000)))
                .isCloseTo(73.5, within(1e-9));
    }

    @Test
    void otherProvidersDefaultToTheInputPriceUntilTheTableSaysOtherwise() {
        ModelEntry unpriced = entry(Provider.OPENAI, null, null);
        ModelEntry priced = entry(Provider.OPENAI, 1.25, null);

        assertThat(LlmRouter.estimateCostUsd(unpriced, usage(2_000_000, 1_000_000, 0, 0))).isCloseTo(20.0, within(1e-9));
        assertThat(LlmRouter.estimateCostUsd(priced, usage(2_000_000, 1_000_000, 0, 0))).isCloseTo(11.25, within(1e-9));
    }

    @Test
    void withoutCachedTokensTheEstimateIsUnchanged() {
        ModelEntry anthropic = entry(Provider.ANTHROPIC, null, null);

        assertThat(LlmRouter.estimateCostUsd(anthropic, usage(1_000_000, 0, 0, 1_000_000))).isCloseTo(60.0, within(1e-9));
    }

    @Test
    void theRouterAppliesCacheRatesToTheAnsweringModelsUsage() {
        ModelEntry row = ModelCapabilityTable.findModel(Provider.ANTHROPIC, "claude-opus-5-5").orElseThrow();
        ProviderAdapter cachedReply = new ProviderAdapter() {
            public Provider id() {
                return Provider.ANTHROPIC;
            }

            public boolean isAvailable() {
                return true;
            }

            public Response send(String model, Request request) {
                return Response.builder().content("ok").usage(usage(1_000_000, 900_000, 0, 0)).build();
            }
        };

        Response response = new LlmRouter(List.of(cachedReply)).complete(Request.builder().prompt("hi")
                .config(RouterConfig.builder().route(List.of(RouteEntry.of(Provider.ANTHROPIC, "claude-opus-5-5"))).build())
                .build());

        double expected = 0.1 * row.getInputCostPerMillionTokens() + 0.9 * row.getCacheReadCostPerMillionTokens();
        assertThat(response.getUsage().getEstimatedCostUsdMicros()).isEqualTo(Math.round(expected * 1_000_000));
        assertThat(response.getUsage().getEstimatedCostUsdCents()).isEqualTo(Math.round(expected * 100));
    }

    @Test
    void theTablesAnthropicRowsCarryTheirPublishedCachePrices() {
        // platform.claude.com pricing, October 2026: reads 0.025x on Fable 5.1, 0.05x on Opus 5.5 and Sonnet 5.5, 0.1x on Haiku 5.5
        assertCachePrices("claude-fable-5-1", 0.25, 12.5, 20);
        assertCachePrices("claude-opus-5-5", 0.2, 5, 8);
        assertCachePrices("claude-sonnet-5-5", 0.1, 2.5, 4);
        assertCachePrices("claude-haiku-5-5", 0.01, 0.125, 0.2);
    }

    private static void assertCachePrices(String model, double read, double write, double longTtlWrite) {
        ModelEntry row = ModelCapabilityTable.findModel(Provider.ANTHROPIC, model).orElseThrow();
        assertThat(row.effectiveCacheReadCostPerMillionTokens()).as(model).isCloseTo(read, within(1e-9));
        assertThat(row.effectiveCacheWriteCostPerMillionTokens()).as(model).isCloseTo(write, within(1e-9));
        assertThat(row.effectiveCacheWriteLongTtlCostPerMillionTokens()).as(model).isCloseTo(longTtlWrite, within(1e-9));
    }

    @Test
    void anOpus55AgentStepIsPricedExactly() {
        ModelEntry opus = ModelCapabilityTable.findModel(Provider.ANTHROPIC, "claude-opus-5-5").orElseThrow();
        // 2,000 plain input at $4, 90,000 read at $0.20, 8,000 written at $5, 1,500 output at $20, per million
        Usage step = usage(100_000, 90_000, 8_000, 1_500);

        assertThat(LlmRouter.estimateCostUsd(opus, step)).isCloseTo(0.008 + 0.018 + 0.04 + 0.03, within(1e-12));
    }

    @Test
    void longLifetimeWritesArePricedAtTheLongRate() {
        ModelEntry anthropic = entry(Provider.ANTHROPIC, null, null);
        Usage writes = Usage.builder().inputTokens(2_000_000).cacheWriteTokens(2_000_000).cacheWriteLongTtlTokens(1_000_000).build();

        assertThat(anthropic.effectiveCacheWriteLongTtlCostPerMillionTokens()).as("Anthropic's hour: 2x").isCloseTo(20.0, within(1e-9));
        // 1M at the five-minute $12.50 and 1M at the hour's $20
        assertThat(LlmRouter.estimateCostUsd(anthropic, writes)).isCloseTo(32.5, within(1e-9));
        assertThat(entry(Provider.OPENAI, null, null).effectiveCacheWriteLongTtlCostPerMillionTokens())
                .as("elsewhere, the default write price until the table says otherwise").isCloseTo(10.0, within(1e-9));
    }

    @Test
    void longLifetimeWritesNeverCountForMoreThanTheWrites() {
        ModelEntry anthropic = entry(Provider.ANTHROPIC, null, null);
        Usage inconsistent = Usage.builder().inputTokens(1_000_000).cacheWriteTokens(1_000_000).cacheWriteLongTtlTokens(5_000_000).build();

        assertThat(LlmRouter.estimateCostUsd(anthropic, inconsistent)).isCloseTo(20.0, within(1e-9));
    }

    @Test
    void cachedTokensBeyondTheInputNeverMakeTheUncachedPartNegative() {
        ModelEntry anthropic = entry(Provider.ANTHROPIC, null, null);

        assertThat(LlmRouter.estimateCostUsd(anthropic, usage(1_000, 2_000_000, 0, 0))).isCloseTo(2.0, within(1e-9));
    }

    @Test
    void overTheLongContextThresholdTheWholeCallIsBilledAtTheHigherRates() {
        ModelEntry haiku = ModelCapabilityTable.findModel(Provider.ANTHROPIC, "claude-haiku-5-5").orElseThrow();
        Usage atThreshold = usage(100_000, 60_000, 10_000, 1_000);
        Usage over = usage(100_001, 60_000, 10_000, 1_000);

        double standard = (30_000 * 0.1 + 60_000 * 0.01 + 10_000 * 0.125 + 1_000 * 0.5) / 1_000_000;
        assertThat(LlmRouter.estimateCostUsd(haiku, atThreshold)).isCloseTo(standard, within(1e-12));
        double longContext = (30_001 * 0.1 + 60_000 * 0.01 + 10_000 * 0.125) * 5 / 1_000_000 + 1_000 * 0.5 * 5 / 1_000_000;
        assertThat(LlmRouter.estimateCostUsd(haiku, over)).isCloseTo(longContext, within(1e-12));

        ModelEntry luna = ModelCapabilityTable.findModel(Provider.OPENAI, "gpt-6-luna").orElseThrow();
        double lunaOver = (300_000 * 0.1 * 2 + 1_000 * 0.5 * 1.5) / 1_000_000;
        assertThat(LlmRouter.estimateCostUsd(luna, usage(300_000, 0, 0, 1_000))).isCloseTo(lunaOver, within(1e-12));
    }

    @Test
    void openAiCacheWritesArePricedAtItsWriteRate() {
        ModelEntry luna = ModelCapabilityTable.findModel(Provider.OPENAI, "gpt-6-luna").orElseThrow();
        // 10,000 plain at $0.10, 80,000 read at $0.01, 10,000 written at $0.125
        assertThat(LlmRouter.estimateCostUsd(luna, usage(100_000, 80_000, 10_000, 0)))
                .isCloseTo((10_000 * 0.1 + 80_000 * 0.01 + 10_000 * 0.125) / 1_000_000, within(1e-12));
    }
}
