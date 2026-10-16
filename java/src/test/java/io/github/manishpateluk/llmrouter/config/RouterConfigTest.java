package io.github.manishpateluk.llmrouter.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import tools.jackson.databind.ObjectMapper;

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
        assertThat(config.getRequiredFeatures()).isEmpty();
        assertThat(config.getFallback()).isEqualTo(FallbackPolicy.SAME_DEPTH);
        assertThat(config.isSendThinkingLevel()).isFalse();
    }

    @Test
    void toBuilderChangesOneFieldAndKeepsEveryOther() {
        RouterConfig original = RouterConfig.builder()
                .thinkingLevel(ThinkingLevel.HIGH)
                .costOptimized(true)
                .structuredOutputStrategy(StructuredOutputStrategy.NATIVE)
                .temperature(0.3)
                .requiredFeatures(Set.of(Feature.TOOLS))
                .fallback(FallbackPolicy.FINISH)
                .sendThinkingLevel(true)
                .build();

        RouterConfig lower = original.toBuilder().thinkingLevel(ThinkingLevel.LOW).build();

        assertThat(lower.getThinkingLevel()).isEqualTo(ThinkingLevel.LOW);
        assertThat(lower.toBuilder().thinkingLevel(ThinkingLevel.HIGH).build()).isEqualTo(original);
    }

    @Test
    void fallbackRoundTripsThroughJsonUsingCanonicalWireValues() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        assertThat(mapper.readValue("{\"fallback\": \"finish\", \"sendThinkingLevel\": true}", RouterConfig.class))
                .satisfies(config -> {
                    assertThat(config.getFallback()).isEqualTo(FallbackPolicy.FINISH);
                    assertThat(config.isSendThinkingLevel()).isTrue();
                });
        assertThat(mapper.writeValueAsString(FallbackPolicy.SAME_DEPTH)).isEqualTo("\"sameDepth\"");
        assertThat(mapper.readValue("{}", RouterConfig.class).getFallback()).isEqualTo(FallbackPolicy.SAME_DEPTH);
    }

    @Test
    void thinkingLevelsStepDownToLowAndNoFurther() {
        assertThat(ThinkingLevel.MAX.lower()).isEqualTo(ThinkingLevel.HIGH);
        assertThat(ThinkingLevel.HIGH.lower()).isEqualTo(ThinkingLevel.MEDIUM);
        assertThat(ThinkingLevel.MEDIUM.lower()).isEqualTo(ThinkingLevel.LOW);
        assertThat(ThinkingLevel.LOW.lower()).isNull();
    }

    @Test
    void requiredFeaturesRoundTripThroughJsonUsingCanonicalWireValues() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        RouterConfig config = mapper.readValue("{\"requiredFeatures\": [\"tools\", \"responseSchema\"]}", RouterConfig.class);

        assertThat(config.getRequiredFeatures()).containsExactlyInAnyOrder(Feature.TOOLS, Feature.RESPONSE_SCHEMA);
        assertThat(mapper.writeValueAsString(Feature.ATTACHMENTS)).isEqualTo("\"attachments\"");
        assertThat(mapper.readValue("{}", RouterConfig.class).getRequiredFeatures()).isEqualTo(Set.of());
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
