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

        // qualifying set for MAX is {opus-5-5, fable-5-1}; opus-5-5 ($4/1M) is cheaper than fable-5-1 ($10/1M)
        List<RouteEntry> resolved = RouteResolver.resolve(config, 1_000_000, NO_PROVIDERS);

        assertThat(resolved).containsExactly(
                RouteEntry.of(Provider.ANTHROPIC, "claude-opus-5-5"),
                RouteEntry.of(Provider.ANTHROPIC, "claude-fable-5-1"));
    }

    @Test
    void defaultRouteUsesOnlyAvailableProvidersInPreferenceOrder() {
        RouterConfig config = RouterConfig.builder().thinkingLevel(ThinkingLevel.MEDIUM).build(); // route omitted
        Set<Provider> available = new LinkedHashSet<>(List.of(Provider.ANTHROPIC, Provider.NVIDIA));

        List<RouteEntry> resolved = RouteResolver.resolve(config, 0, available);

        assertThat(resolved).hasSize(2);
        assertThat(resolved.get(0).getProvider()).isEqualTo(Provider.ANTHROPIC);
        assertThat(resolved.get(0).getModel()).isEqualTo("claude-sonnet-5-5"); // medium tier, established in ModelSelectorTest
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
    void costOptimizedProviderOnlyEntryNeverDisappearsWhenNoModelIsWithinTolerance() {
        // OpenAI's lineup has no model within 1 point of the LOW target; the entry must still expand.
        RouterConfig config = RouterConfig.builder()
                .route(List.of(RouteEntry.of(Provider.OPENAI)))
                .thinkingLevel(ThinkingLevel.LOW)
                .costOptimized(true)
                .build();
        RouterConfig notCostOptimized = RouterConfig.builder()
                .route(List.of(RouteEntry.of(Provider.OPENAI)))
                .thinkingLevel(ThinkingLevel.LOW)
                .build();

        List<RouteEntry> resolved = RouteResolver.resolve(config, 1_000, NO_PROVIDERS);

        assertThat(resolved).isNotEmpty().contains(RouteResolver.resolve(notCostOptimized, 1_000, NO_PROVIDERS).get(0));
    }

    @Test
    void eligibleFilterNarrowsProviderOnlyExpansionBeforeTheHeuristicRuns() {
        RouterConfig config = RouterConfig.builder()
                .route(List.of(RouteEntry.of(Provider.ANTHROPIC)))
                .thinkingLevel(ThinkingLevel.MAX)
                .build();

        List<RouteEntry> resolved = RouteResolver.resolve(config, 0, NO_PROVIDERS,
                model -> !model.getModel().equals("claude-fable-5-1"));

        assertThat(resolved).containsExactly(RouteEntry.of(Provider.ANTHROPIC, "claude-opus-5-5"));
    }

    @Test
    void eligibleFilterAppliesToTheCostOptimizedQualifyingSet() {
        RouterConfig config = RouterConfig.builder()
                .route(List.of(RouteEntry.of(Provider.ANTHROPIC)))
                .thinkingLevel(ThinkingLevel.LOW)
                .costOptimized(true)
                .build();
        List<RouteEntry> unfiltered = RouteResolver.resolve(config, 1_000, NO_PROVIDERS);
        assertThat(unfiltered).hasSizeGreaterThan(1);
        String cheapest = unfiltered.get(0).getModel();

        List<RouteEntry> resolved = RouteResolver.resolve(config, 1_000, NO_PROVIDERS,
                model -> !model.getModel().equals(cheapest));

        assertThat(resolved).isNotEmpty().noneMatch(entry -> cheapest.equals(entry.getModel()));
    }

    @Test
    void providerOnlyEntryWithNoEligibleModelIsReturnedUnexpanded() {
        RouterConfig config = RouterConfig.builder()
                .route(List.of(RouteEntry.of(Provider.PERPLEXITY), RouteEntry.of(Provider.OPENAI, "gpt-6-astra")))
                .build();

        List<RouteEntry> resolved = RouteResolver.resolve(config, 0, NO_PROVIDERS, model -> false);

        // explicit entries are never filtered here — the router checks those per attempt
        assertThat(resolved).containsExactly(RouteEntry.of(Provider.PERPLEXITY), RouteEntry.of(Provider.OPENAI, "gpt-6-astra"));
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
