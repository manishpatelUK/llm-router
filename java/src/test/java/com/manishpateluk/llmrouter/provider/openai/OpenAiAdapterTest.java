package com.manishpateluk.llmrouter.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.manishpateluk.llmrouter.model.Request;
import com.manishpateluk.llmrouter.model.Response;
import com.manishpateluk.llmrouter.provider.Provider;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.completions.CompletionUsage;
import com.openai.services.async.ChatServiceAsync;
import com.openai.services.async.chat.ChatCompletionServiceAsync;
import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.chat.ChatCompletionService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OpenAiAdapterTest {

    @Mock
    private OpenAIClient client;
    @Mock
    private ChatService chatService;
    @Mock
    private com.openai.services.blocking.chat.ChatCompletionService completionService;
    @Mock
    private OpenAIClientAsync asyncClient;
    @Mock
    private ChatServiceAsync asyncChatService;
    @Mock
    private com.openai.services.async.chat.ChatCompletionServiceAsync asyncCompletionService;

    @Test
    void idAndAvailability() {
        assertThat(new OpenAiAdapter(client).id()).isEqualTo(Provider.OPENAI);
        assertThat(new OpenAiAdapter(client).isAvailable()).isTrue();
        assertThat(new OpenAiAdapter((String) null).isAvailable()).isFalse();
        assertThat(new OpenAiAdapter("").isAvailable()).isFalse();
    }

    @Test
    void sendMapsRequestAndParsesTextResponse() {
        when(client.chat()).thenReturn(chatService);
        when(chatService.completions()).thenReturn(completionService);
        ArgumentCaptor<ChatCompletionCreateParams> captor = ArgumentCaptor.forClass(ChatCompletionCreateParams.class);
        when(completionService.create(captor.capture())).thenReturn(textCompletion("Hello there"));

        OpenAiAdapter adapter = new OpenAiAdapter(client);
        Response response = adapter.send("gpt-6-astra", Request.builder().prompt("Hi").systemInstructions("Be terse.").build());

        assertThat(response.getContent()).isEqualTo("Hello there");
        assertThat(response.getToolCalls()).isEmpty();
        assertThat(response.getUsage().getInputTokens()).isEqualTo(10);
        assertThat(response.getUsage().getOutputTokens()).isEqualTo(5);
        assertThat(response.getOriginal()).isInstanceOf(ChatCompletion.class);
        assertThat(captor.getValue().model().toString()).isEqualTo("gpt-6-astra");
    }

    @Test
    void sendIncludesTemperatureAndTopPWhenPresent() {
        when(client.chat()).thenReturn(chatService);
        when(chatService.completions()).thenReturn(completionService);
        ArgumentCaptor<ChatCompletionCreateParams> captor = ArgumentCaptor.forClass(ChatCompletionCreateParams.class);
        when(completionService.create(captor.capture())).thenReturn(textCompletion("ok"));

        OpenAiAdapter adapter = new OpenAiAdapter(client);
        adapter.send("gpt-6-astra", Request.builder().prompt("hi").temperature(0.7).topP(0.9).build());

        assertThat(captor.getValue().temperature()).isEqualTo(Optional.of(0.7));
        assertThat(captor.getValue().topP()).isEqualTo(Optional.of(0.9));
    }

    @Test
    void sendOmitsTemperatureAndTopPWhenAbsent() {
        when(client.chat()).thenReturn(chatService);
        when(chatService.completions()).thenReturn(completionService);
        ArgumentCaptor<ChatCompletionCreateParams> captor = ArgumentCaptor.forClass(ChatCompletionCreateParams.class);
        when(completionService.create(captor.capture())).thenReturn(textCompletion("ok"));

        OpenAiAdapter adapter = new OpenAiAdapter(client);
        adapter.send("gpt-6-astra", Request.builder().prompt("hi").build());

        assertThat(captor.getValue().temperature()).isEmpty();
        assertThat(captor.getValue().topP()).isEmpty();
    }

    @Test
    void sendMapsFunctionToolCallToToolCall() {
        when(client.chat()).thenReturn(chatService);
        when(chatService.completions()).thenReturn(completionService);
        when(completionService.create(any(ChatCompletionCreateParams.class))).thenReturn(toolCallCompletion());

        OpenAiAdapter adapter = new OpenAiAdapter(client);
        Response response = adapter.send("gpt-6-astra", Request.builder().prompt("lookup something").build());

        assertThat(response.getToolCalls()).hasSize(1);
        assertThat(response.getToolCalls().get(0).getName()).isEqualTo("lookup");
        assertThat(response.getToolCalls().get(0).getArguments()).containsEntry("query", "weather");
    }

    @Test
    void sendAsyncUsesTheSdksNativeAsyncClient() {
        when(client.async()).thenReturn(asyncClient);
        when(asyncClient.chat()).thenReturn(asyncChatService);
        when(asyncChatService.completions()).thenReturn(asyncCompletionService);
        when(asyncCompletionService.create(any(ChatCompletionCreateParams.class)))
                .thenReturn(CompletableFuture.completedFuture(textCompletion("async result")));

        OpenAiAdapter adapter = new OpenAiAdapter(client);
        CompletableFuture<Response> future = adapter.sendAsync("gpt-6-astra", Request.builder().prompt("hi").build());

        assertThat(future.join().getContent()).isEqualTo("async result");
    }

    private static ChatCompletion textCompletion(String text) {
        ChatCompletionMessage message = ChatCompletionMessage.builder()
                .content(text)
                .refusal(Optional.empty())
                .toolCalls(List.of())
                .build();
        return fixtureCompletion(message);
    }

    private static ChatCompletion toolCallCompletion() {
        ChatCompletionMessageFunctionToolCall.Function function = ChatCompletionMessageFunctionToolCall.Function.builder()
                .name("lookup")
                .arguments("{\"query\":\"weather\"}")
                .build();
        ChatCompletionMessageFunctionToolCall functionCall = ChatCompletionMessageFunctionToolCall.builder()
                .id("call_1")
                .function(function)
                .build();
        ChatCompletionMessage message = ChatCompletionMessage.builder()
                .content(Optional.empty())
                .refusal(Optional.empty())
                .addToolCall(ChatCompletionMessageToolCall.ofFunction(functionCall))
                .build();
        return fixtureCompletion(message);
    }

    private static ChatCompletion fixtureCompletion(ChatCompletionMessage message) {
        ChatCompletion.Choice choice = ChatCompletion.Choice.builder()
                .index(0)
                .finishReason(ChatCompletion.Choice.FinishReason.STOP)
                .logprobs(Optional.empty())
                .message(message)
                .build();
        return ChatCompletion.builder()
                .id("chatcmpl_1")
                .model("gpt-6-astra")
                .created(0)
                .addChoice(choice)
                .metadata(Optional.empty())
                .moderation(Optional.empty())
                .serviceTier(Optional.empty())
                .usage(CompletionUsage.builder()
                        .promptTokens(10)
                        .completionTokens(5)
                        .totalTokens(15)
                        .build())
                .build();
    }
}
