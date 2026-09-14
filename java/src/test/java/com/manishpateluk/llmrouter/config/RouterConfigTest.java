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
    }

    @Test
    void enumsSerializeToCanonicalLowercaseWireValues() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        assertThat(mapper.writeValueAsString(ThinkingLevel.HIGH)).isEqualTo("\"high\"");
        assertThat(mapper.writeValueAsString(StructuredOutputStrategy.PROMPT_FALLBACK)).isEqualTo("\"promptFallback\"");
        assertThat(mapper.readValue("\"max\"", ThinkingLevel.class)).isEqualTo(ThinkingLevel.MAX);
    }
}
