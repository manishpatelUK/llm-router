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

        double expected = 0.1 * row.getInputCostPerMillionTokens() + 0.9 * row.getInputCostPerMillionTokens() * 0.1;
        assertThat(response.getUsage().getEstimatedCostUsdMicros()).isEqualTo(Math.round(expected * 1_000_000));
    }
}
