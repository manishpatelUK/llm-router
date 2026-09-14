package com.manishpateluk.llmrouter.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.manishpateluk.llmrouter.config.RouteEntry;
import com.manishpateluk.llmrouter.config.RouterConfig;
import com.manishpateluk.llmrouter.config.ThinkingLevel;
import com.manishpateluk.llmrouter.provider.Provider;

import org.junit.jupiter.api.Test;

class RouteResolverTest {

    private static final Set<Provider> NO_PROVIDERS = Set.of();

    @Test
    void fullySpecifiedEntriesArePassedThroughUnchanged() {
        RouterConfig config = RouterConfig.builder()
                .route(List.of(RouteEntry.of(Provider.OPENAI, "gpt-6-astra")))
                .build();

        List<RouteEntry> resolved = RouteResolver.resolve(config, 0, NO_PROVIDERS);

        assertThat(resolved).containsExactly(RouteEntry.of(Provider.OPENAI, "gpt-6-astra"));
    }

    @Test
    void providerOnlyEntryExpandsToSingleModelWhenNotCostOptimized() {
        RouterConfig config = RouterConfig.builder()
                .route(List.of(RouteEntry.of(Provider.ANTHROPIC)))
                .thinkingLevel(ThinkingLevel.MAX)
                .build();

        List<RouteEntry> resolved = RouteResolver.resolve(config, 0, NO_PROVIDERS);

        assertThat(resolved).containsExactly(RouteEntry.of(Provider.ANTHROPIC, "claude-fable-5-1"));
    }

    @Test
    void providerOnlyEntryExpandsToRankedQualifyingSetWhenCostOptimized() {
        RouterConfig config = RouterConfig.builder()
                .route(List.of(RouteEntry.of(Provider.ANTHROPIC)))
                .thinkingLevel(ThinkingLevel.MAX)
                .costOptimized(true)
                .build();

        // qualifying set for MAX is {opus-5, fable-5-1}; opus-5 ($5/1M) is cheaper than fable-5-1 ($10/1M)
        List<RouteEntry> resolved = RouteResolver.resolve(config, 1_000_000, NO_PROVIDERS);

        assertThat(resolved).containsExactly(
                RouteEntry.of(Provider.ANTHROPIC, "claude-opus-5"),
                RouteEntry.of(Provider.ANTHROPIC, "claude-fable-5-1"));
    }

    @Test
    void defaultRouteUsesOnlyAvailableProvidersInPreferenceOrder() {
        RouterConfig config = RouterConfig.builder().thinkingLevel(ThinkingLevel.MEDIUM).build(); // route omitted
        Set<Provider> available = new LinkedHashSet<>(List.of(Provider.ANTHROPIC, Provider.NVIDIA));

        List<RouteEntry> resolved = RouteResolver.resolve(config, 0, available);

        assertThat(resolved).hasSize(2);
        assertThat(resolved.get(0).getProvider()).isEqualTo(Provider.ANTHROPIC);
        assertThat(resolved.get(0).getModel()).isEqualTo("claude-sonnet-5"); // medium tier, established in ModelSelectorTest
        assertThat(resolved.get(1).getProvider()).isEqualTo(Provider.NVIDIA);
        assertThat(resolved.get(1).getModel()).isNotNull();
    }

    @Test
    void defaultRouteIsEmptyWhenNoProvidersAreAvailable() {
        RouterConfig config = RouterConfig.builder().build();

        List<RouteEntry> resolved = RouteResolver.resolve(config, 0, NO_PROVIDERS);

        assertThat(resolved).isEmpty();
    }

    @Test
    void mixedRoutePreservesOrderAndExpandsOnlyProviderOnlyEntries() {
        RouterConfig config = RouterConfig.builder()
                .route(List.of(
                        RouteEntry.of(Provider.OPENAI, "gpt-6-astra"),
                        RouteEntry.of(Provider.ANTHROPIC)))
                .thinkingLevel(ThinkingLevel.MAX)
                .build();

        List<RouteEntry> resolved = RouteResolver.resolve(config, 0, NO_PROVIDERS);

        assertThat(resolved).containsExactly(
                RouteEntry.of(Provider.OPENAI, "gpt-6-astra"),
                RouteEntry.of(Provider.ANTHROPIC, "claude-fable-5-1"));
    }
}
