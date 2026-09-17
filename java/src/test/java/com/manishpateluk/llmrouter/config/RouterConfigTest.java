package com.manishpateluk.llmrouter.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class RouterConfigTest {

    @Test
    void defaultsMatchSpecDefaultsWhenUnset() {
        RouterConfig config = RouterConfig.builder().build();

        assertThat(config.getRoute()).isNull();
        assertThat(config.getThinkingLevel()).isEqualTo(ThinkingLevel.MEDIUM);
        assertThat(config.isCostOptimized()).isFalse();
        assertThat(config.getStructuredOutputStrategy()).isEqualTo(StructuredOutputStrategy.AUTO);
        assertThat(config.getTemperature()).isNull();
        assertThat(config.getTopP()).isNull();
    }

    @Test
    void temperatureAndTopPCanBeSetExplicitly() {
        RouterConfig config = RouterConfig.builder().temperature(0.7).topP(0.9).build();

        assertThat(config.getTemperature()).isEqualTo(0.7);
        assertThat(config.getTopP()).isEqualTo(0.9);
    }

    @Test
    void enumsSerializeToCanonicalLowercaseWireValues() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        assertThat(mapper.writeValueAsString(ThinkingLevel.HIGH)).isEqualTo("\"high\"");
        assertThat(mapper.writeValueAsString(StructuredOutputStrategy.PROMPT_FALLBACK)).isEqualTo("\"promptFallback\"");
        assertThat(mapper.readValue("\"max\"", ThinkingLevel.class)).isEqualTo(ThinkingLevel.MAX);
    }
}
