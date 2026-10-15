package io.github.manishpateluk.llmrouter.capability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import tools.jackson.databind.ObjectMapper;
import io.github.manishpateluk.llmrouter.provider.Provider;

import org.junit.jupiter.api.Test;

class ModelEntryTest {

    @Test
    void inputMediaTypesDecideSupportAndDeriveTheLegacyFlags() {
        ModelEntry imagesOnly = ModelEntry.builder().provider(Provider.OPENROUTER).model("m")
                .inputMediaTypes(List.of("image/png", "image/jpeg")).build();

        assertThat(imagesOnly.supportsMediaType("image/png")).isTrue();
        assertThat(imagesOnly.supportsMediaType("IMAGE/JPG")).isTrue(); // case and the image/jpg alias
        assertThat(imagesOnly.supportsMediaType("image/webp")).isFalse();
        assertThat(imagesOnly.supportsMediaType("application/pdf")).isFalse();
        assertThat(imagesOnly.isSupportsVision()).isTrue();
        assertThat(imagesOnly.isSupportsFileInput()).isFalse();

        ModelEntry text = ModelEntry.builder().provider(Provider.ANTHROPIC).model("m")
                .inputMediaTypes(List.of("text/plain")).build();
        assertThat(text.supportsMediaType("text/plain; charset=utf-8")).isTrue();
        assertThat(text.isSupportsVision()).isFalse();
        assertThat(text.isSupportsFileInput()).isTrue();
    }

    @Test
    void aRowBuiltWithTheLegacyFlagsKeepsTheirOldMeaning() {
        ModelEntry legacy = ModelEntry.builder().provider(Provider.OPENROUTER).model("m")
                .supportsVision(true).supportsFileInput(true).build();

        assertThat(legacy.supportsMediaType("image/avif")).isTrue();       // any image/*
        assertThat(legacy.supportsMediaType("text/csv")).isTrue();         // any non-image
        assertThat(legacy.getInputMediaTypes()).contains("image/png", "application/pdf");

        ModelEntry visionOnly = ModelEntry.builder().provider(Provider.OPENROUTER).model("m").supportsVision(true).build();
        assertThat(visionOnly.supportsMediaType("application/pdf")).isFalse();
    }

    @Test
    void reasoningFieldsDefaultToNone() {
        ModelEntry entry = ModelEntry.builder().provider(Provider.NVIDIA).model("m").build();

        assertThat(entry.getReasoningControl()).isEqualTo(ReasoningControl.NONE);
        assertThat(entry.getReasoningEffortLevels()).isEmpty();
    }

    @Test
    void readsTheNewFieldsFromJsonAndStillReportsTheDerivedFlags() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ModelEntry entry = mapper.readValue("""
                {"provider": "openai", "model": "m", "inputMediaTypes": ["application/pdf"],
                 "maxPdfPages": 100, "reasoningControl": "effort", "reasoningEffortLevels": ["low", "high"]}
                """, ModelEntry.class);

        assertThat(entry.getMaxPdfPages()).isEqualTo(100);
        assertThat(entry.getReasoningControl()).isEqualTo(ReasoningControl.EFFORT);
        assertThat(entry.getReasoningEffortLevels()).containsExactly("low", "high");
        assertThat(mapper.readTree(mapper.writeValueAsString(entry)).path("supportsFileInput").asBoolean()).isTrue();
    }

    @Test
    void everySeededRowDescribesItsInputsAndReasoningExplicitly() {
        for (ModelEntry entry : ModelCapabilityTable.listModels()) {
            assertThat(entry.getInputMediaTypes()).as(entry.getModel()).isNotNull();
            if (entry.getReasoningControl() == ReasoningControl.EFFORT) {
                assertThat(entry.getReasoningEffortLevels()).as(entry.getModel()).isNotEmpty();
            }
        }
        ModelEntry opus = ModelCapabilityTable.findModel(Provider.ANTHROPIC, "claude-opus-5-5").orElseThrow();
        assertThat(opus.supportsMediaType("application/pdf")).isTrue();
        assertThat(opus.getReasoningEffortLevels()).containsExactly("low", "medium", "high", "xhigh", "max");
        ModelEntry haiku = ModelCapabilityTable.findModel(Provider.ANTHROPIC, "claude-haiku-4-5-20251001").orElseThrow();
        assertThat(haiku.getReasoningControl()).isEqualTo(ReasoningControl.BUDGET);
    }
}
