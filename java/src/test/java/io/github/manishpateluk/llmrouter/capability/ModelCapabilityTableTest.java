package io.github.manishpateluk.llmrouter.capability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import io.github.manishpateluk.llmrouter.provider.Provider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ModelCapabilityTableTest {

    private static final String FIXTURE_MODEL = "fixture-model";

    @AfterEach
    void cleanUpRegisteredFixtures() {
        ModelCapabilityTable.removeModel(Provider.OPENROUTER, FIXTURE_MODEL);
        ModelCapabilityTable.removeEmbeddingModel(Provider.OPENROUTER, FIXTURE_MODEL);
    }

    @Test
    void seedEmbeddingPricesLoadAndStayOutOfTheChatModelList() {
        EmbeddingModelEntry small = ModelCapabilityTable.findEmbeddingModel(Provider.OPENAI, "text-embedding-3-small").orElseThrow();

        assertThat(small.getInputCostPerMillionTokens()).isEqualTo(0.02);
        assertThat(small.getDimensions()).isEqualTo(1536);
        assertThat(ModelCapabilityTable.listEmbeddingModels()).extracting(EmbeddingModelEntry::getModel)
                .contains("text-embedding-3-small", "text-embedding-3-large");
        assertThat(ModelCapabilityTable.listModels()).extracting(ModelEntry::getModel)
                .noneMatch(model -> model.startsWith("text-embedding"));
    }

    @Test
    void embeddingModelsCanBeRegisteredAndRemoved() {
        EmbeddingModelEntry fixture = EmbeddingModelEntry.builder()
                .provider(Provider.OPENROUTER).model(FIXTURE_MODEL)
                .inputCostPerMillionTokens(0.5).dimensions(768).maxInputTokens(512)
                .lastUpdated(Instant.parse("2026-01-01T00:00:00Z"))
                .build();

        ModelCapabilityTable.registerEmbeddingModel(fixture);
        assertThat(ModelCapabilityTable.findEmbeddingModel(Provider.OPENROUTER, FIXTURE_MODEL)).contains(fixture);
        assertThat(ModelCapabilityTable.findModel(Provider.OPENROUTER, FIXTURE_MODEL)).isEmpty();

        assertThat(ModelCapabilityTable.removeEmbeddingModel(Provider.OPENROUTER, FIXTURE_MODEL)).isTrue();
        assertThat(ModelCapabilityTable.findEmbeddingModel(Provider.OPENROUTER, FIXTURE_MODEL)).isEmpty();
    }

    @Test
    void loadsSeedModelsFromClasspathResource() {
        List<ModelEntry> models = ModelCapabilityTable.listModels();

        assertThat(models).isNotEmpty();
    }

    @Test
    void seedTableIncludesOpenAiModels() {
        List<ModelEntry> openAiModels = ModelCapabilityTable.listModels(Provider.OPENAI);

        assertThat(openAiModels).isNotEmpty();
        assertThat(openAiModels).allSatisfy(entry -> assertThat(entry.getProvider()).isEqualTo(Provider.OPENAI));
    }

    @Test
    void stringProviderIdOverloadMatchesEnumOverload() {
        List<ModelEntry> byId = ModelCapabilityTable.listModels("openai");
        List<ModelEntry> byEnum = ModelCapabilityTable.listModels(Provider.OPENAI);

        assertThat(byId).isEqualTo(byEnum);
    }

    @Test
    void seedModelsCarryTemperatureAndTopPCapabilityFlags() {
        ModelEntry alwaysOnReasoningModel = ModelCapabilityTable.findModel(Provider.ANTHROPIC, "claude-fable-5-1").orElseThrow();
        assertThat(alwaysOnReasoningModel.isSupportsTemperature()).isFalse();
        assertThat(alwaysOnReasoningModel.isSupportsTopP()).isFalse();

        ModelEntry ordinaryModel = ModelCapabilityTable.findModel(Provider.ANTHROPIC, "claude-haiku-4-5-20251001").orElseThrow();
        assertThat(ordinaryModel.isSupportsTemperature()).isTrue();
        assertThat(ordinaryModel.isSupportsTopP()).isTrue();
    }

    @Test
    void removeModelByStringProviderIdRemovesItFromListModels() {
        ModelCapabilityTable.registerModel(fixtureEntry());
        assertThat(ModelCapabilityTable.findModel(Provider.OPENROUTER, FIXTURE_MODEL)).isPresent();
        assertThat(ModelCapabilityTable.listModels(Provider.OPENROUTER))
                .extracting(ModelEntry::getModel)
                .contains(FIXTURE_MODEL);

        boolean removed = ModelCapabilityTable.removeModel("openrouter", FIXTURE_MODEL);

        assertThat(removed).isTrue();
        Optional<ModelEntry> afterRemoval = ModelCapabilityTable.findModel(Provider.OPENROUTER, FIXTURE_MODEL);
        assertThat(afterRemoval).isEmpty();
        assertThat(ModelCapabilityTable.listModels(Provider.OPENROUTER))
                .extracting(ModelEntry::getModel)
                .doesNotContain(FIXTURE_MODEL);
    }

    @Test
    void removeModelByStringProviderIdReturnsFalseWhenNoMatchingEntry() {
        boolean removed = ModelCapabilityTable.removeModel("openrouter", "no-such-model");

        assertThat(removed).isFalse();
    }

    private static ModelEntry fixtureEntry() {
        return ModelEntry.builder()
                .provider(Provider.OPENROUTER)
                .model(FIXTURE_MODEL)
                .inputCostPerMillionTokens(1.0)
                .outputCostPerMillionTokens(1.0)
                .thinkingScore(5.0)
                .speedScore(5.0)
                .contextWindowTokens(128_000)
                .maxOutputTokens(4_096)
                .supportsStructuredOutput(false)
                .supportsTools(false)
                .supportsVision(false)
                .supportsFileInput(false)
                .supportsFileOutput(false)
                .supportsTemperature(false)
                .supportsTopP(false)
                .lastUpdated(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }
}
