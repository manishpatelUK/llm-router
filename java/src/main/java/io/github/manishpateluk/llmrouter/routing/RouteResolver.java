package io.github.manishpateluk.llmrouter.routing;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

import io.github.manishpateluk.llmrouter.capability.ModelCapabilityTable;
import io.github.manishpateluk.llmrouter.capability.ModelEntry;
import io.github.manishpateluk.llmrouter.config.RouteEntry;
import io.github.manishpateluk.llmrouter.config.RouterConfig;
import io.github.manishpateluk.llmrouter.config.ThinkingLevel;
import io.github.manishpateluk.llmrouter.provider.Provider;

/**
 * Expands a {@link RouterConfig} into an ordered list of fully-specified {@link RouteEntry}
 * candidates — see {@code LIBRARY_SPEC.md} §5.1, §5.2, §5.3.
 *
 * <p>Every {@link RouteEntry} this returns has both {@code provider} and {@code model} set, except
 * a provider-only entry none of whose models qualified, which is left unexpanded for the router
 * to record as skipped. Credential availability is deliberately <em>not</em> checked here for an
 * explicit route (§5.1 says an unavailable candidate is skipped — and recorded as such — at
 * attempt time, not silently removed at resolution time); {@code availableProviders} is only
 * consulted when computing the default route (§5.2), whose entire purpose is to auto-detect
 * what's usable.
 */
public final class RouteResolver {

    private RouteResolver() {
    }

    /**
     * One resolved candidate: the entry to try, the thinking level it was resolved at, and — for
     * a provider-only entry none of whose models qualified — why ({@code unresolvedReason}).
     */
    public record Candidate(RouteEntry entry, ThinkingLevel thinkingLevel, String unresolvedReason) {
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
        return resolve(config, promptTokens, availableProviders, model -> true);
    }

    /**
     * As {@link #resolve(RouterConfig, int, Set)}, but provider-only entries (and the default
     * route) expand only over models matching {@code eligible} — typically "can honor the
     * request's {@code requiredFeatures}" (§5.4). The thinking-level heuristic and cost-optimized
     * qualifying set are both computed over the eligible models alone. Fully-specified entries
     * are never filtered here; the router checks those per attempt.
     */
    public static List<RouteEntry> resolve(
            RouterConfig config, int promptTokens, Set<Provider> availableProviders, Predicate<ModelEntry> eligible) {
        Objects.requireNonNull(eligible, "eligible must not be null");
        return resolveCandidates(config, config.getThinkingLevel(), availableProviders,
                model -> eligible.test(model) ? null : "is not eligible",
                model -> (promptTokens / 1_000_000.0) * model.getInputCostPerMillionTokens(),
                false).stream()
                .map(Candidate::entry)
                .collect(Collectors.toList());
    }

    /**
     * The route's candidates at {@code level}.
     *
     * @param ineligibleReason for each model, why it can't serve this request (e.g.
     *        {@code "does not support required feature(s) [tools]"}), or {@code null} if it can.
     *        Provider-only entries expand only over models with no reason; an entry with none left
     *        comes back unexpanded with an {@code unresolvedReason} built from these reasons.
     * @param estimatedCost the request's estimated cost on a model, for {@code costOptimized}
     *        ordering (§5.3)
     * @param providerOnly when {@code true}, fully-specified entries are left out — how
     *        {@code FallbackPolicy.FINISH} re-resolves the route at a lower level (§5.1.2)
     */
    public static List<Candidate> resolveCandidates(
            RouterConfig config, ThinkingLevel level, Set<Provider> availableProviders,
            Function<ModelEntry, String> ineligibleReason, ToDoubleFunction<ModelEntry> estimatedCost,
            boolean providerOnly) {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(level, "level must not be null");
        Objects.requireNonNull(availableProviders, "availableProviders must not be null");
        Objects.requireNonNull(ineligibleReason, "ineligibleReason must not be null");
        Objects.requireNonNull(estimatedCost, "estimatedCost must not be null");

        List<RouteEntry> route = config.getRoute();
        if (route == null) {
            route = availableProviders.stream().map(RouteEntry::of).collect(Collectors.toList());
        }
        List<Candidate> resolved = new ArrayList<>();
        for (RouteEntry entry : route) {
            if (!entry.isProviderOnly()) {
                // Fully-specified — used exactly as given, never reordered/expanded by cost.
                if (!providerOnly) {
                    resolved.add(new Candidate(entry, level, null));
                }
                continue;
            }
            List<ModelEntry> providerModels = ModelCapabilityTable.listModels(entry.getProvider());
            if (providerModels.isEmpty()) {
                continue;
            }
            Set<String> reasons = new LinkedHashSet<>();
            List<ModelEntry> eligible = new ArrayList<>();
            for (ModelEntry model : providerModels) {
                String reason = ineligibleReason.apply(model);
                if (reason == null) {
                    eligible.add(model);
                } else {
                    reasons.add(reason);
                }
            }
            if (eligible.isEmpty()) {
                // nothing eligible — left unexpanded for the router to record as skipped
                resolved.add(new Candidate(entry, level, "no " + entry.getProvider() + " model qualifies: "
                        + String.join("; ", reasons)));
                continue;
            }
            if (config.isCostOptimized()) {
                List<ModelEntry> qualifying = ModelSelector.selectQualifyingSet(eligible, level);
                for (ModelEntry model : ModelSelector.rankByEstimatedCost(qualifying, estimatedCost)) {
                    resolved.add(new Candidate(RouteEntry.of(model.getProvider(), model.getModel()), level, null));
                }
            } else {
                ModelEntry model = ModelSelector.selectModel(eligible, level);
                resolved.add(new Candidate(RouteEntry.of(model.getProvider(), model.getModel()), level, null));
            }
        }
        return resolved;
    }
}
