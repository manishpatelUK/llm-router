package com.manishpateluk.llmrouter.capability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class ModelCapabilityTableTest {

    @Test
    void loadsSeedModelsFromClasspathResource() {
        List<ModelEntry> models = ModelCapabilityTable.listModels();

        assertThat(models).isNotEmpty();
    }

    @Test
    void seedTableIncludesOpenAiModels() {
        List<ModelEntry> openAiModels = ModelCapabilityTable.listModels("openai");

        assertThat(openAiModels).isNotEmpty();
        assertThat(openAiModels).allSatisfy(entry -> assertThat(entry.getProvider()).isEqualTo("openai"));
    }
}
