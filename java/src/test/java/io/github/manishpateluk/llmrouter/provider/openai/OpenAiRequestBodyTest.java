package io.github.manishpateluk.llmrouter.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.github.manishpateluk.llmrouter.LlmRouter;
import io.github.manishpateluk.llmrouter.capability.ModelCapabilityTable;
import io.github.manishpateluk.llmrouter.config.RouteEntry;
import io.github.manishpateluk.llmrouter.config.RouterConfig;
import io.github.manishpateluk.llmrouter.config.ThinkingLevel;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.model.ToolDefinition;
import io.github.manishpateluk.llmrouter.provider.Provider;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the JSON body actually sent to OpenAI for a reasoning-class model, through the whole
 * pipeline: router negotiation, the adapter, and the real OpenAI SDK serializing the request to a
 * local server. The adapter talks to the Responses API, where GPT-6 models accept function tools
 * with reasoning on (Chat Completions answers that with a 400). Reasoning models also answer 400 to
 * {@code temperature} and {@code top_p}, so the body must carry neither.
 */
class OpenAiRequestBodyTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MODEL = "gpt-6.1-sol";

    private HttpServer server;
    private LlmRouter router;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private volatile String responseBody;
    private volatile String contentType;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::respond);
        server.start();
        OpenAiAdapter adapter = new OpenAiAdapter(OpenAIOkHttpClient.builder().apiKey("test")
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1").build());
        router = new LlmRouter(List.of(adapter));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /** Temperature and top-p requested, as a caller's shared config might, though this model rejects both. */
    private static RouterConfig config() {
        return RouterConfig.builder()
                .route(List.of(RouteEntry.of(Provider.OPENAI, MODEL)))
                .temperature(0.7)
                .topP(0.9)
                .build();
    }

    private static final String TEXT_REPLY = """
            {"id":"resp_1","object":"response","created_at":1,"model":"gpt-6.1-sol","status":"completed",
             "output":[{"type":"message","id":"m1","role":"assistant","status":"completed",
                        "content":[{"type":"output_text","text":"hello","annotations":[]}]}],
             "usage":{"input_tokens":3,"output_tokens":1,"total_tokens":4}}
            """;

    @Test
    void aReasoningModelGetsMaxOutputTokensAndNoRejectedParameters() {
        contentType = "application/json";
        responseBody = TEXT_REPLY;

        Response response = router.complete("hi", config());

        assertThat(response.getContent()).isEqualTo("hello");
        assertThat(response.getDroppedFeatures()).containsExactly("temperature", "topP");
        assertPinnedBody(sentBody());
    }

    @Test
    void toolsAndAThinkingLevelGoInOneRequestToTheResponsesApi() {
        contentType = "application/json";
        responseBody = """
                {"id":"resp_1","object":"response","created_at":1,"model":"gpt-6-luna","status":"completed",
                 "output":[{"type":"reasoning","id":"rs_1","summary":[]},
                           {"type":"function_call","id":"fc_1","call_id":"call_1","name":"lookup","arguments":"{\\"id\\":1}","status":"completed"}],
                 "usage":{"input_tokens":30,"output_tokens":12,"total_tokens":42,
                          "input_tokens_details":{"cached_tokens":0,"cache_write_tokens":0},
                          "output_tokens_details":{"reasoning_tokens":8}}}
                """;

        Response response = router.complete(Request.builder().prompt("Look up document 1.")
                .tools(List.of(ToolDefinition.builder().name("lookup").description("Returns a document.")
                        .parameters(Map.of("type", "object", "properties", Map.of("id", Map.of("type", "integer"))))
                        .build()))
                .config(RouterConfig.builder()
                        .route(List.of(RouteEntry.of(Provider.OPENAI, "gpt-6-luna")))
                        .thinkingLevel(ThinkingLevel.LOW)
                        .sendThinkingLevel(true)
                        .build())
                .build());

        assertThat(requestPath.get()).isEqualTo("/v1/responses");
        JsonNode body = sentBody();
        assertThat(body.path("reasoning").path("effort").asText()).as("reasoning stays on").isNotEmpty().isNotEqualTo("none");
        assertThat(body.path("tools")).hasSize(1);
        assertThat(body.path("tools").path(0).path("type").asText()).isEqualTo("function");
        assertThat(body.path("tools").path(0).path("strict").asBoolean(true)).isFalse();
        assertThat(response.getToolCalls()).singleElement().satisfies(call -> {
            assertThat(call.getId()).isEqualTo("call_1");
            assertThat(call.getArguments()).containsEntry("id", 1);
        });
        assertThat(response.getUsage().getReasoningTokens()).isEqualTo(8);
        assertThat(response.getDroppedFeatures()).doesNotContain("tools", "thinkingLevel");
    }

    @Test
    void theStreamingRequestCarriesTheSameParameters() {
        contentType = "text/event-stream";
        String completed = TEXT_REPLY.replace("\n", "");
        responseBody = event("response.created", "{\"type\":\"response.created\",\"sequence_number\":0,\"response\":"
                        + completed.replace("\"completed\"", "\"in_progress\"") + "}")
                + event("response.output_text.delta", "{\"type\":\"response.output_text.delta\",\"sequence_number\":1,"
                        + "\"item_id\":\"m1\",\"output_index\":0,\"content_index\":0,\"delta\":\"hello\",\"logprobs\":[]}")
                + event("response.completed", "{\"type\":\"response.completed\",\"sequence_number\":2,\"response\":" + completed + "}");

        Response response = router.completeStreaming(
                Request.builder().prompt("hi").config(config()).build(), delta -> { });

        assertThat(response.getContent()).isEqualTo("hello");
        JsonNode body = sentBody();
        assertPinnedBody(body);
        assertThat(body.path("stream").asBoolean()).isTrue();
    }

    @Test
    void aRequestsMaxOutputTokensCapsMaxOutputTokens() {
        contentType = "application/json";
        responseBody = TEXT_REPLY;

        router.complete(Request.builder().prompt("hi").config(config()).maxOutputTokens(1500).build());

        assertThat(sentBody().path("max_output_tokens").asLong()).isEqualTo(1500);
    }

    private void assertPinnedBody(JsonNode body) {
        long expectedMaxOutput = ModelCapabilityTable.findModel(Provider.OPENAI, MODEL).orElseThrow().getMaxOutputTokens();
        assertThat(requestPath.get()).isEqualTo("/v1/responses");
        assertThat(body.path("model").asText()).isEqualTo(MODEL);
        assertThat(body.path("max_output_tokens").asLong()).isEqualTo(expectedMaxOutput);
        assertThat(body.path("store").isBoolean()).as("store is sent explicitly").isTrue();
        assertThat(body.path("store").asBoolean()).as("OpenAI keeps no copy of the response").isFalse();
        assertThat(body.has("previous_response_id")).isFalse();
        assertThat(body.has("max_tokens")).isFalse();
        assertThat(body.has("max_completion_tokens")).isFalse();
        assertThat(body.has("messages")).as("not a Chat Completions body").isFalse();
        assertThat(body.has("temperature")).as("temperature is rejected by this model").isFalse();
        assertThat(body.has("top_p")).as("top_p is rejected by this model").isFalse();
        assertThat(body.path("input").path(0).path("content").asText()).isEqualTo("hi");
    }

    private JsonNode sentBody() {
        return JSON.readTree(requestBody.get());
    }

    private static String event(String name, String data) {
        return "event: " + name + "\ndata: " + data + "\n\n";
    }

    private void respond(HttpExchange exchange) throws IOException {
        requestPath.set(exchange.getRequestURI().getPath());
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
