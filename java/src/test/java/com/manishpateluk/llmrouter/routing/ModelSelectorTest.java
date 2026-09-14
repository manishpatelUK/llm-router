package com.manishpateluk.llmrouter.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import com.manishpateluk.llmrouter.capability.ModelCapabilityTable;
import com.manishpateluk.llmrouter.capability.ModelEntry;
import com.manishpateluk.llmrouter.config.ThinkingLevel;
import com.manishpateluk.llmrouter.provider.Provider;

import org.junit.jupiter.api.Test;

class ModelSelectorTest {

    // Real seed data: Anthropic thinkingScores are {10, 9, 7.5, 6} (fable-5-1, opus-5,
    // sonnet-5, haiku-4-5). min=6, max=10, range=4. Percentile targets interpolate over that
    // range, then we pick whichever actual model is closest to the target — because the scores
    // aren't evenly spaced, low and medium both land on sonnet-5 here; that's a real consequence
    // of §7.2's formula against this data, not a bug.
    private static final List<ModelEntry> ANTHROPIC_MODELS = ModelCapabilityTable.listModels(Provider.ANTHROPIC);

    @Test
    void mediumPicksClosestToThe50thPercentileTarget() {
        // target = 6 + 0.50*4 = 8; sonnet-5 (7.5) is closest (distance 0.5) vs opus-5 (distance 1)
        ModelEntry selected = ModelSelector.selectModel(ANTHROPIC_MODELS, ThinkingLevel.MEDIUM);

        assertThat(selected.getModel()).isEqualTo("claude-sonnet-5");
    }

    @Test
    void lowPicksClosestToThe25thPercentileTarget() {
        // target = 6 + 0.25*4 = 7; sonnet-5 (7.5, distance 0.5) beats haiku-4-5 (6, distance 1)
        ModelEntry selected = ModelSelector.selectModel(ANTHROPIC_MODELS, ThinkingLevel.LOW);

        assertThat(selected.getModel()).isEqualTo("claude-sonnet-5");
    }

    @Test
    void highPicksExactMatchAtThe75thPercentileTarget() {
        // target = 6 + 0.75*4 = 9; opus-5 matches exactly
        ModelEntry selected = ModelSelector.selectModel(ANTHROPIC_MODELS, ThinkingLevel.HIGH);

        assertThat(selected.getModel()).isEqualTo("claude-opus-5");
    }

    @Test
    void maxPicksTheHighestThinkingScoreModel() {
        ModelEntry selected = ModelSelector.selectModel(ANTHROPIC_MODELS, ThinkingLevel.MAX);

        assertThat(selected.getModel()).isEqualTo("claude-fable-5-1");
    }

    @Test
    void qualifyingSetIncludesEverythingWithinOnePointOfTarget() {
        // target = 10 (max); within 1.0: fable-5-1 (distance 0), opus-5 (distance 1).
        // sonnet-5 (distance 2.5) and haiku-4-5 (distance 4) are excluded.
        List<ModelEntry> qualifying = ModelSelector.selectQualifyingSet(ANTHROPIC_MODELS, ThinkingLevel.MAX);

        assertThat(qualifying).extracting(ModelEntry::getModel)
                .containsExactlyInAnyOrder("claude-fable-5-1", "claude-opus-5");
    }

    @Test
    void rankByEstimatedCostOrdersAscendingByInputCost() {
        List<ModelEntry> qualifying = ModelSelector.selectQualifyingSet(ANTHROPIC_MODELS, ThinkingLevel.MAX);

        // fable-5-1: $10/1M input, opus-5: $5/1M input -> opus-5 is cheaper for any prompt size
        List<ModelEntry> ranked = ModelSelector.rankByEstimatedCost(qualifying, 1_000_000);

        assertThat(ranked).extracting(ModelEntry::getModel)
                .containsExactly("claude-opus-5", "claude-fable-5-1");
    }

    @Test
    void tieBreaksOnSpeedThenCostThenAlphabeticalModelId() {
        // Three synthetic models with identical thinkingScore (so distance-to-target ties):
        ModelEntry fastest = fixture("z-model", /* thinkingScore */ 7, /* speedScore */ 9, /* cost */ 1.0);
        ModelEntry cheaperSlow = fixture("a-model", 7, 5, 1.0);
        ModelEntry pricierSlow = fixture("b-model", 7, 5, 2.0);

        ModelEntry selected = ModelSelector.selectModel(List.of(pricierSlow, cheaperSlow, fastest), ThinkingLevel.MEDIUM);

        // Highest speedScore wins the tie-break regardless of cost or alphabetical order
        assertThat(selected.getModel()).isEqualTo("z-model");
    }

    @Test
    void tieBreakFallsBackToCostThenAlphabeticalWhenSpeedTies() {
        ModelEntry cheaper = fixture("b-model", 7, 5, 1.0);
        ModelEntry pricier = fixture("a-model", 7, 5, 2.0);

        ModelEntry selected = ModelSelector.selectModel(List.of(pricier, cheaper), ThinkingLevel.MEDIUM);

        assertThat(selected.getModel()).isEqualTo("b-model");
    }

    @Test
    void emptyModelListIsRejected() {
        assertThatThrownBy(() -> ModelSelector.selectModel(List.of(), ThinkingLevel.MEDIUM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ModelEntry fixture(String model, double thinkingScore, double speedScore, double costPerMillion) {
        return ModelEntry.builder()
                .provider(Provider.ANTHROPIC)
                .model(model)
                .inputCostPerMillionTokens(costPerMillion)
                .outputCostPerMillionTokens(costPerMillion)
                .thinkingScore(thinkingScore)
                .speedScore(speedScore)
                .contextWindowTokens(128_000)
                .maxOutputTokens(4_096)
                .supportsStructuredOutput(true)
                .supportsTools(true)
                .supportsVision(false)
                .supportsFileInput(false)
                .supportsFileOutput(false)
                .lastUpdated(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }
}
