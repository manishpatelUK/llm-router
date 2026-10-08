package io.github.manishpateluk.llmrouter.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.github.manishpateluk.llmrouter.LlmRouter;
import io.github.manishpateluk.llmrouter.capability.ModelCapabilityTable;
import io.github.manishpateluk.llmrouter.config.RouteEntry;
import io.github.manishpateluk.llmrouter.config.RouterConfig;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
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
 * local server. Reasoning models (GPT-6, o-series) answer 400 to {@code max_tokens},
 * {@code temperature} and {@code top_p}, so the body must carry {@code max_completion_tokens}
 * and none of those.
 */
class OpenAiRequestBodyTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MODEL = "gpt-6.1-sol";

    private HttpServer server;
    private LlmRouter router;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
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

    @Test
    void aReasoningModelGetsMaxCompletionTokensAndNoRejectedParameters() {
        contentType = "application/json";
        responseBody = """
                {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"gpt-6.1-sol",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"hello","refusal":null},
                             "finish_reason":"stop","logprobs":null}],
                 "usage":{"prompt_tokens":3,"completion_tokens":1,"total_tokens":4}}
                """;

        Response response = router.complete("hi", config());

        assertThat(response.getContent()).isEqualTo("hello");
        assertThat(response.getDroppedFeatures()).containsExactly("temperature", "topP");
        assertPinnedBody(sentBody());
    }

    @Test
    void theStreamingRequestCarriesTheSameParameters() {
        contentType = "text/event-stream";
        String chunk = "{\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-6.1-sol\",\"choices\":%s%s}";
        responseBody = "data: " + chunk.formatted("[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"hello\"},\"finish_reason\":\"stop\"}]", "") + "\n\n"
                + "data: " + chunk.formatted("[]", ",\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1,\"total_tokens\":4}") + "\n\n"
                + "data: [DONE]\n\n";

        Response response = router.completeStreaming(
                Request.builder().prompt("hi").config(config()).build(), delta -> { });

        assertThat(response.getContent()).isEqualTo("hello");
        JsonNode body = sentBody();
        assertPinnedBody(body);
        assertThat(body.path("stream").asBoolean()).isTrue();
    }

    private static void assertPinnedBody(JsonNode body) {
        long expectedMaxOutput = ModelCapabilityTable.findModel(Provider.OPENAI, MODEL).orElseThrow().getMaxOutputTokens();
        assertThat(body.path("model").asText()).isEqualTo(MODEL);
        assertThat(body.path("max_completion_tokens").asLong()).isEqualTo(expectedMaxOutput);
        assertThat(body.has("max_tokens")).as("max_tokens is rejected by reasoning models").isFalse();
        assertThat(body.has("temperature")).as("temperature is rejected by this model").isFalse();
        assertThat(body.has("top_p")).as("top_p is rejected by this model").isFalse();
    }

    private JsonNode sentBody() {
        return JSON.readTree(requestBody.get());
    }

    private void respond(HttpExchange exchange) throws IOException {
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
