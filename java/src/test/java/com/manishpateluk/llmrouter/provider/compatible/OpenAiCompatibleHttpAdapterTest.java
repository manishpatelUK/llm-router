package com.manishpateluk.llmrouter.provider.compatible;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manishpateluk.llmrouter.model.Attachment;
import com.manishpateluk.llmrouter.model.Request;
import com.manishpateluk.llmrouter.model.Response;
import com.manishpateluk.llmrouter.model.ToolDefinition;
import com.manishpateluk.llmrouter.provider.Provider;
import com.manishpateluk.llmrouter.provider.compatible.HttpTransport.HttpRequestRecord;
import com.manishpateluk.llmrouter.provider.compatible.HttpTransport.HttpResponseRecord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OpenAiCompatibleHttpAdapterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock
    private HttpTransport transport;

    @Test
    void idAndAvailability() {
        assertThat(new PerplexityAdapter("key", transport).id()).isEqualTo(Provider.PERPLEXITY);
        assertThat(new PerplexityAdapter("key", transport).isAvailable()).isTrue();
        assertThat(new PerplexityAdapter(null, transport).isAvailable()).isFalse();
        assertThat(new PerplexityAdapter("", transport).isAvailable()).isFalse();
    }

    @Test
    void sendBuildsExpectedRequestBodyAndParsesResponse() throws Exception {
        ArgumentCaptor<HttpRequestRecord> captor = ArgumentCaptor.forClass(HttpRequestRecord.class);
        when(transport.send(captor.capture())).thenReturn(textResponse("Hello there", 10, 5));

        PerplexityAdapter adapter = new PerplexityAdapter("secret-key", transport);
        Response response = adapter.send("sonar-pro", Request.builder()
                .prompt("Hi")
                .systemInstructions("Be terse.")
                .build());

        assertThat(response.getContent()).isEqualTo("Hello there");
        assertThat(response.getUsage().getInputTokens()).isEqualTo(10);
        assertThat(response.getUsage().getOutputTokens()).isEqualTo(5);

        HttpRequestRecord sent = captor.getValue();
        assertThat(sent.url()).isEqualTo("https://api.perplexity.ai/chat/completions");
        assertThat(sent.headers()).containsEntry("Authorization", "Bearer secret-key");

        JsonNode body = JSON.readTree(sent.jsonBody());
        assertThat(body.path("model").asText()).isEqualTo("sonar-pro");
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(body.path("messages").get(0).path("content").asText()).isEqualTo("Be terse.");
        assertThat(body.path("messages").get(1).path("content").asText()).isEqualTo("Hi");
    }

    @Test
    void sendIncludesToolsAndResponseFormatWhenPresent() throws Exception {
        ArgumentCaptor<HttpRequestRecord> captor = ArgumentCaptor.forClass(HttpRequestRecord.class);
        when(transport.send(captor.capture())).thenReturn(textResponse("ok", 1, 1));

        PerplexityAdapter adapter = new PerplexityAdapter("key", transport);
        adapter.send("sonar-pro", Request.builder()
                .prompt("hi")
                .tools(List.of(ToolDefinition.builder().name("lookup").description("d").parameters(Map.of("type", "object")).build()))
                .responseSchema(Map.of("type", "object", "properties", Map.of()))
                .build());

        JsonNode body = JSON.readTree(captor.getValue().jsonBody());
        assertThat(body.path("tools").get(0).path("function").path("name").asText()).isEqualTo("lookup");
        assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_schema");
    }

    @Test
    void sendIncludesTemperatureAndTopPWhenPresent() throws Exception {
        ArgumentCaptor<HttpRequestRecord> captor = ArgumentCaptor.forClass(HttpRequestRecord.class);
        when(transport.send(captor.capture())).thenReturn(textResponse("ok", 1, 1));

        PerplexityAdapter adapter = new PerplexityAdapter("key", transport);
        adapter.send("sonar-pro", Request.builder().prompt("hi").temperature(0.7).topP(0.9).build());

        JsonNode body = JSON.readTree(captor.getValue().jsonBody());
        assertThat(body.path("temperature").asDouble()).isEqualTo(0.7);
        assertThat(body.path("top_p").asDouble()).isEqualTo(0.9);
    }

    @Test
    void sendOmitsTemperatureAndTopPWhenAbsent() throws Exception {
        ArgumentCaptor<HttpRequestRecord> captor = ArgumentCaptor.forClass(HttpRequestRecord.class);
        when(transport.send(captor.capture())).thenReturn(textResponse("ok", 1, 1));

        PerplexityAdapter adapter = new PerplexityAdapter("key", transport);
        adapter.send("sonar-pro", Request.builder().prompt("hi").build());

        JsonNode body = JSON.readTree(captor.getValue().jsonBody());
        assertThat(body.has("temperature")).isFalse();
        assertThat(body.has("top_p")).isFalse();
    }

    @Test
    void sendMapsImageAttachmentToImageUrlContentPart() throws Exception {
        ArgumentCaptor<HttpRequestRecord> captor = ArgumentCaptor.forClass(HttpRequestRecord.class);
        when(transport.send(captor.capture())).thenReturn(textResponse("ok", 1, 1));

        PerplexityAdapter adapter = new PerplexityAdapter("key", transport);
        adapter.send("sonar-pro", Request.builder()
                .prompt("describe this")
                .attachments(List.of(Attachment.builder().mediaType("image/png").data(new byte[]{1, 2, 3}).build()))
                .build());

        JsonNode lastMessage = JSON.readTree(captor.getValue().jsonBody()).path("messages").get(0);
        assertThat(lastMessage.path("content").get(0).path("type").asText()).isEqualTo("text");
        assertThat(lastMessage.path("content").get(1).path("type").asText()).isEqualTo("image_url");
        assertThat(lastMessage.path("content").get(1).path("image_url").path("url").asText()).startsWith("data:image/png;base64,");
    }

    @Test
    void sendMapsToolCallsFromResponse() {
        when(transport.send(any())).thenReturn(new HttpResponseRecord(200, """
                {
                  "choices": [{"message": {"content": null, "tool_calls": [
                    {"id": "call_1", "function": {"name": "lookup", "arguments": "{\\"query\\":\\"weather\\"}"}}
                  ]}}],
                  "usage": {"prompt_tokens": 8, "completion_tokens": 4}
                }
                """));

        PerplexityAdapter adapter = new PerplexityAdapter("key", transport);
        Response response = adapter.send("sonar-pro", Request.builder().prompt("lookup something").build());

        assertThat(response.getToolCalls()).hasSize(1);
        assertThat(response.getToolCalls().get(0).getName()).isEqualTo("lookup");
        assertThat(response.getToolCalls().get(0).getArguments()).containsEntry("query", "weather");
    }

    @Test
    void nonSuccessStatusThrowsProviderHttpException() {
        when(transport.send(any())).thenReturn(new HttpResponseRecord(429, "{\"error\":\"rate limited\"}"));

        PerplexityAdapter adapter = new PerplexityAdapter("key", transport);

        assertThatThrownBy(() -> adapter.send("sonar-pro", Request.builder().prompt("hi").build()))
                .isInstanceOf(ProviderHttpException.class)
                .satisfies(e -> assertThat(((ProviderHttpException) e).statusCode()).isEqualTo(429));
    }

    @Test
    void sendAsyncDelegatesToTransportSendAsync() {
        when(transport.sendAsync(any())).thenReturn(CompletableFuture.completedFuture(textResponse("async result", 1, 1)));

        PerplexityAdapter adapter = new PerplexityAdapter("key", transport);
        CompletableFuture<Response> future = adapter.sendAsync("sonar-pro", Request.builder().prompt("hi").build());

        assertThat(future.join().getContent()).isEqualTo("async result");
    }

    @Test
    void theOtherThreeCompatibleAdaptersHaveTheirOwnProviderIdAndEndpoint() {
        assertThat(new NvidiaAdapter("k", transport).id()).isEqualTo(Provider.NVIDIA);
        assertThat(new HuggingFaceAdapter("k", transport).id()).isEqualTo(Provider.HUGGINGFACE);
        assertThat(new OpenRouterAdapter("k", transport).id()).isEqualTo(Provider.OPENROUTER);

        when(transport.send(any())).thenReturn(textResponse("ok", 1, 1));
        new NvidiaAdapter("k", transport).send("m", Request.builder().prompt("hi").build());
        new HuggingFaceAdapter("k", transport).send("m", Request.builder().prompt("hi").build());
        new OpenRouterAdapter("k", transport).send("m", Request.builder().prompt("hi").build());
    }

    private static HttpResponseRecord textResponse(String content, int inputTokens, int outputTokens) {
        String body = """
                {
                  "choices": [{"message": {"content": "%s", "tool_calls": []}}],
                  "usage": {"prompt_tokens": %d, "completion_tokens": %d}
                }
                """.formatted(content, inputTokens, outputTokens);
        return new HttpResponseRecord(200, body);
    }
}
