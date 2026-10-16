package io.github.manishpateluk.llmrouter.provider.perplexity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.github.manishpateluk.llmrouter.model.Attachment;
import io.github.manishpateluk.llmrouter.model.Citation;
import io.github.manishpateluk.llmrouter.model.Message;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.model.ToolCall;
import io.github.manishpateluk.llmrouter.model.ToolDefinition;
import io.github.manishpateluk.llmrouter.provider.Provider;
import io.github.manishpateluk.llmrouter.provider.compatible.HttpTransport;
import io.github.manishpateluk.llmrouter.provider.compatible.HttpTransport.HttpRequestRecord;
import io.github.manishpateluk.llmrouter.provider.compatible.HttpTransport.HttpResponseRecord;
import io.github.manishpateluk.llmrouter.provider.compatible.ProviderHttpException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PerplexityAdapterTest {

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

    // ---- request ----

    @Test
    void sendPostsAnAgentRequestWithWebSearchOn() throws Exception {
        JsonNode body = sendAndCapture("perplexity/sonar", Request.builder()
                .prompt("Hi")
                .systemInstructions("Be terse.")
                .history(List.of(Message.system("Use British spelling."), Message.user("Earlier"), Message.assistant("Reply")))
                .build());

        assertThat(body.path("model").asText()).isEqualTo("perplexity/sonar");
        assertThat(body.has("preset")).isFalse();
        assertThat(body.path("instructions").asText()).isEqualTo("Be terse.\n\nUse British spelling.");
        assertThat(body.path("input")).extracting(item -> item.path("role").asText(), item -> item.path("content").asText())
                .containsExactly(tuple("user", "Earlier"), tuple("assistant", "Reply"), tuple("user", "Hi"));
        assertThat(body.path("input").get(0).path("type").asText()).isEqualTo("message");
        assertThat(body.path("tools")).hasSize(1);
        assertThat(body.path("tools").get(0).path("type").asText()).isEqualTo("web_search");
        assertThat(body.path("max_output_tokens").asLong()).isEqualTo(8000); // perplexity/sonar's table limit
        assertThat(body.has("stream")).isFalse();
    }

    @Test
    void sendGoesToTheAgentEndpointWithBearerAuth() {
        ArgumentCaptor<HttpRequestRecord> captor = ArgumentCaptor.forClass(HttpRequestRecord.class);
        when(transport.send(captor.capture())).thenReturn(textResponse("ok"));

        new PerplexityAdapter("secret-key", transport).send("perplexity/sonar", Request.builder().prompt("Hi").build());

        assertThat(captor.getValue().url()).isEqualTo("https://api.perplexity.ai/v1/agent");
        assertThat(captor.getValue().headers()).containsEntry("Authorization", "Bearer secret-key");
    }

    @Test
    void aPresetModelIsSentAsAPresetWithItsOwnToolsAndLimit() throws Exception {
        JsonNode body = sendAndCapture("preset:fast", Request.builder().prompt("Hi").build());

        assertThat(body.path("preset").asText()).isEqualTo("fast");
        assertThat(body.has("model")).isFalse();
        assertThat(body.has("tools")).isFalse();
        assertThat(body.has("max_output_tokens")).isFalse();

        body = sendAndCapture("preset:low", Request.builder().prompt("Hi").maxOutputTokens(500).build());
        assertThat(body.path("max_output_tokens").asLong()).isEqualTo(500);
    }

    @Test
    void maxOutputTokensIsCappedByTheRequestAndDefaultsForModelsTheTableDoesNotList() throws Exception {
        assertThat(sendAndCapture("perplexity/sonar", Request.builder().prompt("Hi").maxOutputTokens(300).build())
                .path("max_output_tokens").asLong()).isEqualTo(300);
        assertThat(sendAndCapture("anthropic/claude-sonnet-5-5", Request.builder().prompt("Hi").build())
                .path("max_output_tokens").asLong()).isEqualTo(4096);
    }

    @Test
    void samplingReasoningToolsAndSchemaAreSentInAgentApiShapes() throws Exception {
        JsonNode body = sendAndCapture("perplexity/kimi-k3", Request.builder()
                .prompt("hi")
                .temperature(0.7)
                .topP(0.9)
                .reasoningEffort("high")
                .reasoningBudgetTokens(2048)
                .tools(List.of(ToolDefinition.builder().name("lookup").description("d").parameters(Map.of("type", "object")).build()))
                .responseSchema(Map.of("type", "object", "properties", Map.of()))
                .build());

        assertThat(body.path("temperature").asDouble()).isEqualTo(0.7);
        assertThat(body.path("top_p").asDouble()).isEqualTo(0.9);
        assertThat(body.path("reasoning").path("effort").asText()).isEqualTo("high");
        assertThat(body.path("reasoning").has("budget_tokens")).isFalse();
        assertThat(body.path("tools")).extracting(tool -> tool.path("type").asText()).containsExactly("web_search", "function");
        assertThat(body.path("tools").get(1).path("name").asText()).isEqualTo("lookup");
        assertThat(body.path("tools").get(1).path("parameters").path("type").asText()).isEqualTo("object");
        assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_schema");
        assertThat(body.path("response_format").path("json_schema").path("name").asText()).isEqualTo("response");
        assertThat(body.path("response_format").path("json_schema").path("schema").path("type").asText()).isEqualTo("object");
    }

    @Test
    void optionalFieldsAreOmittedWhenAbsent() throws Exception {
        JsonNode body = sendAndCapture("perplexity/sonar", Request.builder().prompt("hi").build());

        assertThat(body.has("temperature")).isFalse();
        assertThat(body.has("top_p")).isFalse();
        assertThat(body.has("reasoning")).isFalse();
        assertThat(body.has("response_format")).isFalse();
        assertThat(body.has("instructions")).isFalse();
    }

    @Test
    void attachmentsGoAsInputImageAndInputFileDataUris() throws Exception {
        JsonNode body = sendAndCapture("perplexity/sonar", Request.builder()
                .prompt("read these")
                .attachments(List.of(
                        Attachment.builder().mediaType("image/png").data(new byte[]{1, 2, 3}).build(),
                        Attachment.builder().mediaType("application/pdf").data(new byte[]{1, 2, 3}).filename("a.pdf").build(),
                        Attachment.builder().mediaType("text/plain").data(new byte[]{4}).build()))
                .build());

        JsonNode parts = body.path("input").get(0).path("content");
        assertThat(parts).hasSize(4);
        assertThat(parts.get(0).path("type").asText()).isEqualTo("input_text");
        assertThat(parts.get(0).path("text").asText()).isEqualTo("read these");
        assertThat(parts.get(1).path("type").asText()).isEqualTo("input_image");
        assertThat(parts.get(1).path("image_url").asText()).isEqualTo("data:image/png;base64,AQID");
        assertThat(parts.get(2).path("type").asText()).isEqualTo("input_file");
        assertThat(parts.get(2).path("file_data").asText()).isEqualTo("data:application/pdf;base64,AQID");
        assertThat(parts.get(2).path("filename").asText()).isEqualTo("a.pdf");
        assertThat(parts.get(3).path("file_data").asText()).startsWith("data:text/plain;base64,");
        assertThat(parts.get(3).has("filename")).isFalse();
    }

    @Test
    void toolHistoryIsReplayedAsFunctionCallItems() throws Exception {
        JsonNode body = sendAndCapture("perplexity/kimi-k3", Request.builder()
                .prompt("And tomorrow?")
                .history(List.of(
                        Message.user("Weather in Paris?"),
                        Message.assistant("", List.of(ToolCall.builder().id("call_1").name("weather").arguments(Map.of("city", "Paris")).build())),
                        Message.tool("call_1", "{\"temp\":18}"),
                        Message.tool("uncorrelated result")))
                .build());

        JsonNode input = body.path("input");
        assertThat(input).extracting(item -> item.path("type").asText())
                .containsExactly("message", "function_call", "function_call_output", "message", "message");
        assertThat(input.get(1).path("call_id").asText()).isEqualTo("call_1");
        assertThat(input.get(1).path("name").asText()).isEqualTo("weather");
        assertThat(JSON.readTree(input.get(1).path("arguments").asText()).path("city").asText()).isEqualTo("Paris");
        assertThat(input.get(2).path("call_id").asText()).isEqualTo("call_1");
        assertThat(input.get(2).path("output").asText()).isEqualTo("{\"temp\":18}");
        assertThat(input.get(3).path("content").asText()).isEqualTo("Tool result: uncorrelated result");
    }

    // ---- response ----

    @Test
    void sendReadsTextUsageReportedCostAndCitationsOrderedById() {
        when(transport.send(any())).thenReturn(new HttpResponseRecord(200, """
                {
                  "id": "resp_1", "object": "response", "status": "completed", "model": "perplexity/sonar",
                  "output": [
                    {"type": "search_results", "queries": ["capital of france"], "results": [
                      {"id": 2, "url": "https://example.org/seine", "title": "The Seine", "snippet": "A river in France."},
                      {"id": 1, "url": "https://example.org/paris", "title": "Paris", "snippet": ""}
                    ]},
                    {"type": "search_results", "queries": ["more"], "results": [
                      {"id": 3, "url": "https://example.org/paris", "title": "Paris again"}
                    ]},
                    {"type": "message", "role": "assistant", "status": "completed", "content": [
                      {"type": "output_text", "text": "Paris is the capital [1], ", "annotations": [
                        {"type": "url_citation", "url": "https://example.org/extra", "title": "Extra"}
                      ]},
                      {"type": "output_text", "text": "on the Seine [2].", "annotations": []}
                    ]}
                  ],
                  "usage": {
                    "input_tokens": 50, "output_tokens": 20, "total_tokens": 70,
                    "output_tokens_details": {"reasoning_tokens": 4},
                    "cost": {"currency": "USD", "input_cost": 0.0000125, "output_cost": 0.00005, "tool_calls_cost": 0.0025, "total_cost": 0.0025625}
                  }
                }
                """));

        Response response = new PerplexityAdapter("key", transport).send("perplexity/sonar", Request.builder().prompt("Capital of France?").build());

        assertThat(response.getContent()).isEqualTo("Paris is the capital [1], on the Seine [2].");
        assertThat(response.getUsage().getInputTokens()).isEqualTo(50);
        assertThat(response.getUsage().getOutputTokens()).isEqualTo(20);
        assertThat(response.getUsage().getReasoningTokens()).isEqualTo(4);
        assertThat(response.getUsage().getEstimatedCostUsdMicros()).isEqualTo(2563);
        assertThat(response.getCitations()).extracting(Citation::getUrl, Citation::getTitle, Citation::getSnippet).containsExactly(
                tuple("https://example.org/paris", "Paris", null),
                tuple("https://example.org/seine", "The Seine", "A river in France."),
                tuple("https://example.org/extra", "Extra", null));
        assertThat(response.getToolCalls()).isEmpty();
        assertThat(response.getOriginal()).isNotNull();
    }

    @Test
    void sendMapsFunctionCallItemsToToolCalls() {
        when(transport.send(any())).thenReturn(new HttpResponseRecord(200, """
                {"status": "completed", "output": [
                  {"type": "function_call", "id": "fc_1", "call_id": "call_9", "name": "lookup",
                   "arguments": "{\\"query\\":\\"weather\\"}", "status": "completed"}
                ], "usage": {"input_tokens": 8, "output_tokens": 4, "total_tokens": 12}}
                """));

        Response response = new PerplexityAdapter("key", transport).send("perplexity/kimi-k3", Request.builder().prompt("lookup").build());

        assertThat(response.getContent()).isEmpty();
        assertThat(response.getToolCalls()).hasSize(1);
        assertThat(response.getToolCalls().get(0).getId()).isEqualTo("call_9");
        assertThat(response.getToolCalls().get(0).getName()).isEqualTo("lookup");
        assertThat(response.getToolCalls().get(0).getArguments()).containsEntry("query", "weather");
        assertThat(response.getUsage().getEstimatedCostUsdMicros()).isZero();
        assertThat(response.getCitations()).isEmpty();
    }

    @Test
    void aFailedRunFailsTheAttempt() {
        when(transport.send(any())).thenReturn(new HttpResponseRecord(200, """
                {"status": "failed", "output": [], "error": {"message": "upstream model unavailable"}}
                """));

        assertThatThrownBy(() -> new PerplexityAdapter("key", transport).send("perplexity/sonar", Request.builder().prompt("hi").build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("upstream model unavailable");
    }

    @Test
    void nonSuccessStatusThrowsProviderHttpException() {
        when(transport.send(any())).thenReturn(new HttpResponseRecord(429, "{\"error\":\"rate limited\"}"));

        assertThatThrownBy(() -> new PerplexityAdapter("key", transport).send("perplexity/sonar", Request.builder().prompt("hi").build()))
                .isInstanceOf(ProviderHttpException.class)
                .satisfies(e -> assertThat(((ProviderHttpException) e).statusCode()).isEqualTo(429));
    }

    @Test
    void sendAsyncDelegatesToTransportSendAsync() {
        when(transport.sendAsync(any())).thenReturn(CompletableFuture.completedFuture(textResponse("async result")));

        CompletableFuture<Response> future = new PerplexityAdapter("key", transport)
                .sendAsync("perplexity/sonar", Request.builder().prompt("hi").build());

        assertThat(future.join().getContent()).isEqualTo("async result");
    }

    @Test
    void noCredentialsFailsBeforeSending() {
        assertThatThrownBy(() -> new PerplexityAdapter(null, transport).send("perplexity/sonar", Request.builder().prompt("hi").build()))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- streaming ----

    @Test
    void streamingDeliversTextDeltasAndTakesTheRestFromResponseCompleted() throws Exception {
        ArgumentCaptor<HttpRequestRecord> captor = ArgumentCaptor.forClass(HttpRequestRecord.class);
        when(transport.sendStreaming(captor.capture(), any())).thenAnswer(invocation -> {
            Consumer<String> onLine = invocation.getArgument(1);
            onLine.accept("event: response.created");
            onLine.accept("data: {\"type\":\"response.created\",\"sequence_number\":0,\"response\":{\"status\":\"in_progress\",\"output\":[]}}");
            onLine.accept("");
            onLine.accept("data: {\"type\":\"response.reasoning.search_queries\",\"sequence_number\":1,\"queries\":[\"q\"]}");
            onLine.accept("data: {\"type\":\"response.output_text.delta\",\"sequence_number\":2,\"delta\":\"Paris \"}");
            onLine.accept("data: {\"type\":\"response.output_text.delta\",\"sequence_number\":3,\"delta\":\"[1].\"}");
            onLine.accept("data: {\"type\":\"response.completed\",\"sequence_number\":4,\"response\":{\"status\":\"completed\",\"output\":["
                    + "{\"type\":\"search_results\",\"results\":[{\"id\":1,\"url\":\"https://example.org/paris\",\"title\":\"Paris\"}]},"
                    + "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"Paris [1].\"}]}],"
                    + "\"usage\":{\"input_tokens\":3,\"output_tokens\":2,\"cost\":{\"total_cost\":0.001}}}}");
            onLine.accept("data: [DONE]");
            return new HttpResponseRecord(200, "");
        });
        List<String> deltas = new ArrayList<>();

        Response response = new PerplexityAdapter("key", transport)
                .sendStreaming("perplexity/sonar", Request.builder().prompt("Hey").build(), deltas::add);

        assertThat(JSON.readTree(captor.getValue().jsonBody()).path("stream").asBoolean()).isTrue();
        assertThat(deltas).containsExactly("Paris ", "[1].");
        assertThat(response.getContent()).isEqualTo("Paris [1].");
        assertThat(response.getUsage().getInputTokens()).isEqualTo(3);
        assertThat(response.getUsage().getOutputTokens()).isEqualTo(2);
        assertThat(response.getUsage().getEstimatedCostUsdMicros()).isEqualTo(1000);
        assertThat(response.getCitations()).extracting(Citation::getUrl).containsExactly("https://example.org/paris");
        assertThat(response.getOriginal()).isInstanceOfSatisfying(JsonNode.class, events -> assertThat(events.size()).isEqualTo(5));
    }

    @Test
    void streamingFallsBackToCompletedItemsWhenNoCompletedEventArrives() {
        when(transport.sendStreaming(any(), any())).thenAnswer(invocation -> {
            Consumer<String> onLine = invocation.getArgument(1);
            onLine.accept("data: {\"type\":\"response.output_item.done\",\"sequence_number\":0,\"output_index\":0,\"item\":"
                    + "{\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"now\",\"arguments\":\"{}\"}}");
            return new HttpResponseRecord(200, "");
        });

        Response response = new PerplexityAdapter("key", transport)
                .sendStreaming("perplexity/kimi-k3", Request.builder().prompt("Time?").build(), text -> { });

        assertThat(response.getToolCalls()).extracting(ToolCall::getId, ToolCall::getName).containsExactly(tuple("call_1", "now"));
        assertThat(response.getContent()).isEmpty();
    }

    @Test
    void streamingFailsOnResponseFailed() {
        when(transport.sendStreaming(any(), any())).thenAnswer(invocation -> {
            Consumer<String> onLine = invocation.getArgument(1);
            onLine.accept("data: {\"type\":\"response.failed\",\"sequence_number\":0,\"error\":{\"message\":\"boom\"}}");
            return new HttpResponseRecord(200, "");
        });

        assertThatThrownBy(() -> new PerplexityAdapter("key", transport)
                .sendStreaming("perplexity/sonar", Request.builder().prompt("hi").build(), text -> { }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom");
    }

    @Test
    void streamingRaisesAnHttpErrorWithItsBody() {
        when(transport.sendStreaming(any(), any())).thenReturn(new HttpResponseRecord(400, "{\"error\":{\"message\":\"bad model\"}}"));

        assertThatThrownBy(() -> new PerplexityAdapter("key", transport)
                .sendStreaming("perplexity/sonar", Request.builder().prompt("hi").build(), text -> { }))
                .isInstanceOf(ProviderHttpException.class)
                .hasMessageContaining("bad model");
    }

    private JsonNode sendAndCapture(String model, Request request) throws Exception {
        ArgumentCaptor<HttpRequestRecord> captor = ArgumentCaptor.forClass(HttpRequestRecord.class);
        when(transport.send(captor.capture())).thenReturn(textResponse("ok"));
        new PerplexityAdapter("key", transport).send(model, request);
        return JSON.readTree(captor.getValue().jsonBody());
    }

    private static HttpResponseRecord textResponse(String text) {
        return new HttpResponseRecord(200, """
                {"status": "completed", "output": [
                  {"type": "message", "role": "assistant", "content": [{"type": "output_text", "text": "%s", "annotations": []}]}
                ], "usage": {"input_tokens": 1, "output_tokens": 1, "total_tokens": 2}}
                """.formatted(text));
    }
}
