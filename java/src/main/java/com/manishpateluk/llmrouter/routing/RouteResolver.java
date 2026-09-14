package com.manishpateluk.llmrouter.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.manishpateluk.llmrouter.capability.ModelCapabilityTable;
import com.manishpateluk.llmrouter.capability.ModelEntry;
import com.manishpateluk.llmrouter.config.RouteEntry;
import com.manishpateluk.llmrouter.config.RouterConfig;
import com.manishpateluk.llmrouter.provider.Provider;

/**
 * Expands a {@link RouterConfig} into an ordered list of fully-specified {@link RouteEntry}
 * candidates — see {@code LIBRARY_SPEC.md} §5.1, §5.2, §5.3.
 *
 * <p>Every {@link RouteEntry} this returns has both {@code provider} and {@code model} set.
 * Credential availability is deliberately <em>not</em> checked here for an explicit route (§5.1
 * says an unavailable candidate is skipped — and recorded as such — at attempt time, not
 * silently removed at resolution time); {@code availableProviders} is only consulted when
 * computing the default route (§5.2), whose entire purpose is to auto-detect what's usable.
 */
public final class RouteResolver {

    private RouteResolver() {
    }

    /**
     * @param promptTokens the prompt's approximate token count (§5.3), used only when
     *                      {@code config.isCostOptimized()} expands a provider-only entry
     * @param availableProviders providers to fall back to when {@code config.getRoute()} is
     *                            {@code null}, in preference order (§6.2) — typically each
     *                            {@code Provider} whose {@code ProviderAdapter.isAvailable()} is
     *                            {@code true}
     */
    public static List<RouteEntry> resolve(RouterConfig config, int promptTokens, Set<Provider> availableProviders) {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(availableProviders, "availableProviders must not be null");

        List<RouteEntry> route = config.getRoute();
        if (route == null) {
            route = availableProviders.stream().map(RouteEntry::of).collect(Collectors.toList());
        }
        return expand(route, config, promptTokens);
    }

    private static List<RouteEntry> expand(List<RouteEntry> route, RouterConfig config, int promptTokens) {
        List<RouteEntry> resolved = new ArrayList<>();
        for (RouteEntry entry : route) {
            if (!entry.isProviderOnly()) {
                // Fully-specified — used exactly as given, never reordered/expanded by cost.
                resolved.add(entry);
                continue;
            }
            List<ModelEntry> providerModels = ModelCapabilityTable.listModels(entry.getProvider());
            if (providerModels.isEmpty()) {
                continue;
            }
            if (config.isCostOptimized()) {
                List<ModelEntry> qualifying = ModelSelector.selectQualifyingSet(providerModels, config.getThinkingLevel());
                for (ModelEntry model : ModelSelector.rankByEstimatedCost(qualifying, promptTokens)) {
                    resolved.add(RouteEntry.of(model.getProvider(), model.getModel()));
                }
            } else {
                ModelEntry model = ModelSelector.selectModel(providerModels, config.getThinkingLevel());
                resolved.add(RouteEntry.of(model.getProvider(), model.getModel()));
            }
        }
        return resolved;
    }
}
