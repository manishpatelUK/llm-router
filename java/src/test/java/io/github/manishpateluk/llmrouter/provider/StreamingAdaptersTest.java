package io.github.manishpateluk.llmrouter.provider;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.model.ToolDefinition;
import io.github.manishpateluk.llmrouter.provider.anthropic.AnthropicAdapter;
import io.github.manishpateluk.llmrouter.provider.openai.OpenAiAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The native streaming paths of the Anthropic and OpenAI adapters, exercised through the real SDK
 * clients against a local server replaying each provider's server-sent-event wire format.
 */
class StreamingAdaptersTest {

    private HttpServer server;
    private String base;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private volatile String sse;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::respond);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void anthropicStreamsTextDeltasAndAccumulatesTheFullMessage() {
        sse = anthropicEvents(
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hel\"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"lo there\"}}",
                "{\"type\":\"content_block_stop\",\"index\":0}");
        AnthropicAdapter adapter = new AnthropicAdapter(AnthropicOkHttpClient.builder().apiKey("test").baseUrl(base).build());
        List<String> deltas = new ArrayList<>();

        Response response = adapter.sendStreaming("claude-opus-5", Request.builder().prompt("Hi").build(), deltas::add);

        assertThat(deltas).containsExactly("Hel", "lo there");
        assertThat(response.getContent()).isEqualTo("Hello there");
        assertThat(response.getUsage().getInputTokens()).isEqualTo(10);
        assertThat(response.getUsage().getOutputTokens()).isEqualTo(5);
        assertThat(requestBody.get()).contains("\"stream\":true");
    }

    @Test
    void anthropicAccumulatesStreamedToolCalls() {
        sse = anthropicEvents(
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"lookup\",\"input\":{}}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"city\\\":\"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\" \\\"Paris\\\"}\"}}",
                "{\"type\":\"content_block_stop\",\"index\":0}");
        AnthropicAdapter adapter = new AnthropicAdapter(AnthropicOkHttpClient.builder().apiKey("test").baseUrl(base).build());
        List<String> deltas = new ArrayList<>();

        Response response = adapter.sendStreaming("claude-opus-5", Request.builder().prompt("Weather?")
                .tools(List.of(ToolDefinition.builder().name("lookup").description("d").parameters(Map.of("type", "object")).build()))
                .build(), deltas::add);

        assertThat(deltas).isEmpty();
        assertThat(response.getToolCalls()).singleElement().satisfies(call -> {
            assertThat(call.getId()).isEqualTo("toolu_1");
            assertThat(call.getName()).isEqualTo("lookup");
            assertThat(call.getArguments()).containsEntry("city", "Paris");
        });
    }

    @Test
    void openAiStreamsChunksAndRequestsUsageInTheFinalChunk() {
        String chunk = "{\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-6-astra\",\"choices\":%s%s}";
        sse = String.join("", List.of(
                event(chunk.formatted("[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Hel\"},\"finish_reason\":null}]", "")),
                event(chunk.formatted("[{\"index\":0,\"delta\":{\"content\":\"lo there\"},\"finish_reason\":null}]", "")),
                event(chunk.formatted("[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]", "")),
                event(chunk.formatted("[]", ",\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":2,\"total_tokens\":9}")),
                event("[DONE]")));
        OpenAiAdapter adapter = new OpenAiAdapter(OpenAIOkHttpClient.builder().apiKey("test").baseUrl(base + "/v1").build());
        List<String> deltas = new ArrayList<>();

        Response response = adapter.sendStreaming("gpt-6-astra", Request.builder().prompt("Hi").build(), deltas::add);

        assertThat(deltas).containsExactly("Hel", "lo there");
        assertThat(response.getContent()).isEqualTo("Hello there");
        assertThat(response.getUsage().getInputTokens()).isEqualTo(7);
        assertThat(response.getUsage().getOutputTokens()).isEqualTo(2);
        assertThat(requestBody.get()).contains("\"stream\":true", "\"include_usage\":true");
    }

    /** Wraps content events in Anthropic's message_start ... message_stop envelope, each with its {@code event:} line as the real API sends. */
    private static String anthropicEvents(String... contentEvents) {
        StringBuilder out = new StringBuilder(anthropicEvent("{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\","
                + "\"role\":\"assistant\",\"model\":\"claude-opus-5\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}}"));
        for (String contentEvent : contentEvents) {
            out.append(anthropicEvent(contentEvent));
        }
        out.append(anthropicEvent("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},"
                + "\"usage\":{\"output_tokens\":5}}"));
        out.append(anthropicEvent("{\"type\":\"message_stop\"}"));
        return out.toString();
    }

    private static String anthropicEvent(String data) {
        String type = data.replaceFirst("^\\{\"type\":\"([a-z_]+)\".*$", "$1");
        return "event: " + type + "\n" + event(data);
    }

    private static String event(String data) {
        return "data: " + data + "\n\n";
    }

    private void respond(HttpExchange exchange) throws IOException {
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
