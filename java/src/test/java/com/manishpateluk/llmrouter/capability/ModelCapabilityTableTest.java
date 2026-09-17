package com.manishpateluk.llmrouter.capability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.manishpateluk.llmrouter.provider.Provider;

import org.junit.jupiter.api.Test;

class ModelCapabilityTableTest {

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
        assertThat(alwaysOnReasoningModel.isSupportsTopP()).isTrue();

        ModelEntry ordinaryModel = ModelCapabilityTable.findModel(Provider.ANTHROPIC, "claude-sonnet-5").orElseThrow();
        assertThat(ordinaryModel.isSupportsTemperature()).isTrue();
        assertThat(ordinaryModel.isSupportsTopP()).isTrue();
    }
}
