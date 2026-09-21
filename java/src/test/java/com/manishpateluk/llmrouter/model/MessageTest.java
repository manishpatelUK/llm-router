package com.manishpateluk.llmrouter.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MessageTest {

    @Test
    void plainFactoriesDefaultToNoCorrelationFields() {
        assertThat(Message.user("hi").getToolCalls()).isEmpty();
        assertThat(Message.assistant("hi").getToolCalls()).isEmpty();
        assertThat(Message.assistant("hi").getToolCallId()).isNull();
        assertThat(Message.system("hi").getToolCalls()).isEmpty();
        assertThat(Message.tool("hi").getToolCalls()).isEmpty();
        assertThat(Message.tool("hi").getToolCallId()).isNull();
    }

    @Test
    void assistantWithToolCallsCarriesThemForReplay() {
        ToolCall call = ToolCall.builder().id("call_1").name("lookup").arguments(Map.of("q", "x")).build();

        Message message = Message.assistant("looking that up", List.of(call));

        assertThat(message.getRole()).isEqualTo(Role.ASSISTANT);
        assertThat(message.getContent()).isEqualTo("looking that up");
        assertThat(message.getToolCalls()).containsExactly(call);
        assertThat(message.getToolCallId()).isNull();
    }

    @Test
    void assistantWithNullToolCallsDefaultsToEmptyRatherThanNull() {
        assertThat(Message.assistant("hi", null).getToolCalls()).isEmpty();
    }

    @Test
    void toolWithCorrelationIdCarriesItForNativeThreading() {
        Message message = Message.tool("call_1", "sunny");

        assertThat(message.getRole()).isEqualTo(Role.TOOL);
        assertThat(message.getContent()).isEqualTo("sunny");
        assertThat(message.getToolCallId()).isEqualTo("call_1");
        assertThat(message.getToolCalls()).isEmpty();
    }
}
