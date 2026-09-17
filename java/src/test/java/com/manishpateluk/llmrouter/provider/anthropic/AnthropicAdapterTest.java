package com.manishpateluk.llmrouter.provider.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.AnthropicClientAsync;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.Usage;
import com.anthropic.services.async.MessageServiceAsync;
import com.anthropic.services.blocking.MessageService;
import com.manishpateluk.llmrouter.model.Request;
import com.manishpateluk.llmrouter.model.Response;
import com.manishpateluk.llmrouter.provider.Provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AnthropicAdapterTest {

    @Mock
    private AnthropicClient client;
    @Mock
    private MessageService messageService;
    @Mock
    private AnthropicClientAsync asyncClient;
    @Mock
    private MessageServiceAsync asyncMessageService;

    @Test
    void idAndAvailability() {
        assertThat(new AnthropicAdapter(client).id()).isEqualTo(Provider.ANTHROPIC);
        assertThat(new AnthropicAdapter(client).isAvailable()).isTrue();
        assertThat(new AnthropicAdapter((String) null).isAvailable()).isFalse();
        assertThat(new AnthropicAdapter("").isAvailable()).isFalse();
    }

    @Test
    void sendMapsRequestAndParsesTextResponse() {
        when(client.messages()).thenReturn(messageService);
        when(messageService.create(any(MessageCreateParams.class))).thenReturn(textMessage("Hello there"));

        AnthropicAdapter adapter = new AnthropicAdapter(client);
        Response response = adapter.send("claude-opus-5", Request.builder().prompt("Hi").build());

        assertThat(response.getContent()).isEqualTo("Hello there");
        assertThat(response.getToolCalls()).isEmpty();
        assertThat(response.getUsage().getInputTokens()).isEqualTo(10);
        assertThat(response.getUsage().getOutputTokens()).isEqualTo(5);
        assertThat(response.getOriginal()).isInstanceOf(Message.class);
    }

    @Test
    void sendSendsSystemInstructionsAndPrompt() {
        when(client.messages()).thenReturn(messageService);
        ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        when(messageService.create(captor.capture())).thenReturn(textMessage("ok"));

        AnthropicAdapter adapter = new AnthropicAdapter(client);
        adapter.send("claude-opus-5", Request.builder()
                .prompt("What's the weather?")
                .systemInstructions("You are terse.")
                .build());

        MessageCreateParams sent = captor.getValue();
        assertThat(sent.model().toString()).isEqualTo("claude-opus-5");
        assertThat(sent.system()).isPresent();
    }

    @Test
    void sendIncludesTemperatureAndTopPWhenPresent() {
        when(client.messages()).thenReturn(messageService);
        ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        when(messageService.create(captor.capture())).thenReturn(textMessage("ok"));

        AnthropicAdapter adapter = new AnthropicAdapter(client);
        adapter.send("claude-opus-5", Request.builder().prompt("hi").temperature(0.7).topP(0.9).build());

        assertThat(captor.getValue().temperature()).isEqualTo(java.util.Optional.of(0.7));
        assertThat(captor.getValue().topP()).isEqualTo(java.util.Optional.of(0.9));
    }

    @Test
    void sendOmitsTemperatureAndTopPWhenAbsent() {
        when(client.messages()).thenReturn(messageService);
        ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        when(messageService.create(captor.capture())).thenReturn(textMessage("ok"));

        AnthropicAdapter adapter = new AnthropicAdapter(client);
        adapter.send("claude-opus-5", Request.builder().prompt("hi").build());

        assertThat(captor.getValue().temperature()).isEmpty();
        assertThat(captor.getValue().topP()).isEmpty();
    }

    @Test
    void sendMapsToolUseBlockToToolCall() {
        when(client.messages()).thenReturn(messageService);
        when(messageService.create(any(MessageCreateParams.class))).thenReturn(toolUseMessage());

        AnthropicAdapter adapter = new AnthropicAdapter(client);
        Response response = adapter.send("claude-opus-5", Request.builder().prompt("lookup something").build());

        assertThat(response.getToolCalls()).hasSize(1);
        assertThat(response.getToolCalls().get(0).getName()).isEqualTo("lookup");
        assertThat(response.getToolCalls().get(0).getArguments()).containsEntry("query", "weather");
    }

    @Test
    void sendAsyncUsesTheSdksNativeAsyncClient() {
        when(client.async()).thenReturn(asyncClient);
        when(asyncClient.messages()).thenReturn(asyncMessageService);
        when(asyncMessageService.create(any(MessageCreateParams.class)))
                .thenReturn(CompletableFuture.completedFuture(textMessage("async result")));

        AnthropicAdapter adapter = new AnthropicAdapter(client);
        CompletableFuture<Response> future = adapter.sendAsync("claude-opus-5", Request.builder().prompt("hi").build());

        assertThat(future.join().getContent()).isEqualTo("async result");
    }

    private static Usage fixtureUsage(long inputTokens, long outputTokens) {
        return Usage.builder()
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .cacheCreation(com.anthropic.models.messages.CacheCreation.builder()
                        .ephemeral1hInputTokens(0)
                        .ephemeral5mInputTokens(0)
                        .build())
                .cacheCreationInputTokens(0L)
                .cacheReadInputTokens(0L)
                .inferenceGeo(java.util.Optional.empty())
                .outputTokensDetails(java.util.Optional.empty())
                .serverToolUse(java.util.Optional.empty())
                .serviceTier(java.util.Optional.empty())
                .build();
    }

    private static Message.Builder fixtureMessageBuilder(String id) {
        return Message.builder()
                .id(id)
                .model("claude-opus-5")
                .container(java.util.Optional.empty())
                .stopDetails(java.util.Optional.empty())
                .stopReason(java.util.Optional.empty())
                .stopSequence(java.util.Optional.empty());
    }

    private static Message textMessage(String text) {
        return fixtureMessageBuilder("msg_1")
                .content(List.of(ContentBlock.ofText(
                        TextBlock.builder().text(text).citations(List.of()).build())))
                .usage(fixtureUsage(10, 5))
                .build();
    }

    private static Message toolUseMessage() {
        ToolUseBlock toolUse = ToolUseBlock.builder()
                .id("call_1")
                .name("lookup")
                .input(com.anthropic.core.JsonValue.from(Map.of("query", "weather")))
                .caller(com.anthropic.models.messages.ToolUseBlock.Caller.ofDirect(
                        com.anthropic.models.messages.DirectCaller.builder().build()))
                .build();
        return fixtureMessageBuilder("msg_2")
                .content(List.of(ContentBlock.ofToolUse(toolUse)))
                .usage(fixtureUsage(8, 4))
                .build();
    }
}
