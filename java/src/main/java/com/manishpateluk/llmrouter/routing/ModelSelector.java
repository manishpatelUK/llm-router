package com.manishpateluk.llmrouter.routing;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.manishpateluk.llmrouter.capability.ModelEntry;
import com.manishpateluk.llmrouter.config.ThinkingLevel;

/**
 * Implements the thinking-level → model selection heuristic from {@code LIBRARY_SPEC.md} §7.2:
 * resolving a provider-only route entry to a concrete model, and computing the "qualifying set"
 * used by cost-optimized ordering (§5.3).
 */
public final class ModelSelector {

    private static final Map<ThinkingLevel, Double> TARGET_PERCENTILE = Map.of(
            ThinkingLevel.LOW, 0.25,
            ThinkingLevel.MEDIUM, 0.50,
            ThinkingLevel.HIGH, 0.75,
            ThinkingLevel.MAX, 1.00);

    private static final double QUALIFYING_SET_TOLERANCE = 1.0;

    private ModelSelector() {
    }

    /**
     * Resolves a provider's models to the single best match for {@code level}, per §7.2 steps
     * 1-5 (percentile target score, closest-match, then tie-break: higher speed, lower combined
     * cost, alphabetical model id).
     *
     * @throws IllegalArgumentException if {@code providerModels} is empty
     */
    public static ModelEntry selectModel(List<ModelEntry> providerModels, ThinkingLevel level) {
        List<ModelEntry> models = requireNonEmpty(providerModels);
        double targetScore = targetScore(models, level);
        double minDistance = models.stream()
                .mapToDouble(m -> distance(m, targetScore))
                .min()
                .orElseThrow();

        return models.stream()
                .filter(m -> distance(m, targetScore) == minDistance)
                .min(Comparator
                        .comparingDouble(ModelEntry::getSpeedScore).reversed()
                        .thenComparingDouble(ModelSelector::combinedCostPerMillionTokens)
                        .thenComparing(ModelEntry::getModel))
                .orElseThrow();
    }

    /**
     * All of a provider's models within a 1-point {@code thinkingScore} tolerance band of the
     * §7.2 target score for {@code level} — the cost-optimized "qualifying set" (§5.3), unsorted.
     * Rank it with {@link #rankByEstimatedCost} once the prompt's token count is known.
     *
     * @throws IllegalArgumentException if {@code providerModels} is empty
     */
    public static List<ModelEntry> selectQualifyingSet(List<ModelEntry> providerModels, ThinkingLevel level) {
        List<ModelEntry> models = requireNonEmpty(providerModels);
        double targetScore = targetScore(models, level);

        return models.stream()
                .filter(m -> distance(m, targetScore) <= QUALIFYING_SET_TOLERANCE)
                .collect(Collectors.toList());
    }

    /**
     * Sorts a qualifying set ascending by estimated cost for a prompt of {@code promptTokens}
     * tokens (§5.3) — cheapest first. Ranks purely on estimated input cost (the qualifying
     * set's output length isn't known in advance, so input cost is the available cost signal for
     * ranking candidates against each other).
     */
    public static List<ModelEntry> rankByEstimatedCost(List<ModelEntry> qualifyingSet, int promptTokens) {
        Objects.requireNonNull(qualifyingSet, "qualifyingSet must not be null");
        return qualifyingSet.stream()
                .sorted(Comparator.comparingDouble(m -> estimatedInputCost(m, promptTokens)))
                .collect(Collectors.toList());
    }

    private static double targetScore(List<ModelEntry> models, ThinkingLevel level) {
        double min = models.stream().mapToDouble(ModelEntry::getThinkingScore).min().orElseThrow();
        double max = models.stream().mapToDouble(ModelEntry::getThinkingScore).max().orElseThrow();
        double percentile = TARGET_PERCENTILE.get(level);
        return min + percentile * (max - min);
    }

    private static double distance(ModelEntry entry, double targetScore) {
        return Math.abs(entry.getThinkingScore() - targetScore);
    }

    private static double combinedCostPerMillionTokens(ModelEntry entry) {
        return entry.getInputCostPerMillionTokens() + entry.getOutputCostPerMillionTokens();
    }

    private static double estimatedInputCost(ModelEntry entry, int promptTokens) {
        return (promptTokens / 1_000_000.0) * entry.getInputCostPerMillionTokens();
    }

    private static List<ModelEntry> requireNonEmpty(List<ModelEntry> models) {
        Objects.requireNonNull(models, "models must not be null");
        if (models.isEmpty()) {
            throw new IllegalArgumentException("models must not be empty");
        }
        return models;
    }
}
