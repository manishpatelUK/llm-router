package io.github.manishpateluk.llmrouter.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import io.github.manishpateluk.llmrouter.model.Attachment;
import io.github.manishpateluk.llmrouter.model.Message;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.ToolCall;
import io.github.manishpateluk.llmrouter.model.ToolDefinition;
import io.github.manishpateluk.llmrouter.provider.compatible.HttpTransport;
import io.github.manishpateluk.llmrouter.provider.compatible.NvidiaAdapter;
import io.github.manishpateluk.llmrouter.provider.compatible.OpenRouterAdapter;
import io.github.manishpateluk.llmrouter.provider.openai.OpenAiAdapter;
import io.github.manishpateluk.llmrouter.provider.perplexity.PerplexityAdapter;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

/**
 * Providers that cache on their own (OpenAI, the OpenAI-compatible ones, Perplexity) only cache a
 * prefix the next call repeats byte for byte (§4.3). For each of them, the body sent for step N+1 of
 * a tool loop must start with step N's system instructions and conversation, unchanged, and carry no
 * cache markers of any kind.
 */
class PrefixStabilityAcrossAdaptersTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void openAi() throws IOException {
        List<String> bodies = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] reply = """
                    {"id":"resp_1","object":"response","created_at":1,"model":"gpt-6-luna","status":"completed",
                     "output":[{"type":"message","id":"m1","role":"assistant","status":"completed",
                                "content":[{"type":"output_text","text":"ok","annotations":[]}]}],
                     "usage":{"input_tokens":3,"output_tokens":1,"total_tokens":4}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
        try {
            OpenAiAdapter adapter = new OpenAiAdapter(OpenAIOkHttpClient.builder().apiKey("test")
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1").build());
            runSteps(request -> {
                adapter.send("gpt-6-luna", request);
                JsonNode body = parse(bodies.getLast());
                assertThat(body.path("instructions").asText()).startsWith("You are a researcher.")
                        .contains("Summary of the earlier conversation");
                JsonNode input = body.path("input");
                assertThat(body.toString().split("prompt_cache_breakpoint", -1)).as("one breakpoint per request").hasSize(2);
                assertThat(input.path(input.size() - 2).toString()).as("on the last history item")
                        .contains("\"prompt_cache_breakpoint\":{\"mode\":\"explicit\"}");
                return body;
            }, body -> withoutBreakpoints(body.path("input")), body -> body.path("tools"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void nvidia() {
        List<String> bodies = new ArrayList<>();
        NvidiaAdapter adapter = new NvidiaAdapter("key", recording(bodies, CHAT_REPLY));
        runSteps(request -> {
            adapter.send("some-model", request);
            return parse(bodies.getLast());
        }, body -> body.path("messages"), body -> body.path("tools"));
    }

    @Test
    void openRouter() {
        List<String> bodies = new ArrayList<>();
        OpenRouterAdapter adapter = new OpenRouterAdapter("key", recording(bodies, CHAT_REPLY));
        runSteps(request -> {
            adapter.send("google/gemini-3.8-flash", request);
            return parse(bodies.getLast());
        }, body -> body.path("messages"), body -> body.path("tools"));
    }

    @Test
    void perplexity() {
        List<String> bodies = new ArrayList<>();
        PerplexityAdapter adapter = new PerplexityAdapter("key", recording(bodies, """
                {"status": "completed", "output": [
                  {"type": "message", "role": "assistant", "content": [{"type": "output_text", "text": "ok", "annotations": []}]}
                ], "usage": {"input_tokens": 1, "output_tokens": 1, "total_tokens": 2}}
                """));
        runSteps(request -> {
            adapter.send("perplexity/sonar", request);
            JsonNode body = parse(bodies.getLast());
            assertThat(body.path("instructions").asText()).contains("You are a researcher.");
            return body;
        }, body -> body.path("input"), body -> body.path("tools"));
    }

    private static final String CHAT_REPLY = """
            {"choices":[{"message":{"role":"assistant","content":"ok"}}],
             "usage":{"prompt_tokens":3,"completion_tokens":1}}
            """;

    private static HttpTransport recording(List<String> bodies, String reply) {
        return request -> {
            bodies.add(request.jsonBody());
            return new HttpTransport.HttpResponseRecord(200, reply);
        };
    }

    /**
     * Four steps of a tool loop, the goal and a file shown in the first message and a conversation
     * summary as a {@code SYSTEM} turn, as llm-agent-loop sends them; checks every consecutive pair.
     */
    private static void runSteps(Function<Request, JsonNode> send, Function<JsonNode, JsonNode> conversationOf,
            Function<JsonNode, JsonNode> toolsOf) {
        List<Message> history = new ArrayList<>(List.of(
                Message.system("Summary of the earlier conversation: the user likes tables."),
                Message.user("Research the market for widgets.", List.of(Attachment.builder()
                        .mediaType("image/png").data(new byte[] {(byte) 0x89, 'P', 'N', 'G'}).filename("chart.png").build()))));
        List<JsonNode> bodies = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            bodies.add(send.apply(Request.builder()
                    .systemInstructions("You are a researcher.")
                    .tools(List.of(tool("search"), tool("fetch")))
                    .history(List.copyOf(history))
                    .prompt("Continue toward the goal above." + (i == 2 ? "\n\nRelevant memory:\n- widgets are blue" : ""))
                    .build()));
            ToolCall call = ToolCall.builder().id("call_" + i).name("search").arguments(Map.of("q", "widgets " + i)).build();
            history.add(Message.assistant(i % 2 == 0 ? "" : "Searching.", List.of(call)));
            history.add(Message.tool("call_" + i, "result " + i));
        }

        for (int i = 0; i + 1 < bodies.size(); i++) {
            JsonNode earlier = bodies.get(i);
            JsonNode later = bodies.get(i + 1);
            assertThat(earlier.toString()).doesNotContain("cache_control");
            assertThat(toolsOf.apply(later)).isEqualTo(toolsOf.apply(earlier));
            JsonNode earlierTurns = conversationOf.apply(earlier);
            JsonNode laterTurns = conversationOf.apply(later);
            assertThat(laterTurns.size()).isGreaterThan(earlierTurns.size());
            for (int m = 0; m < earlierTurns.size() - 1; m++) { // all but the prompt
                assertThat(laterTurns.path(m)).as("step %d, turn %d", i + 1, m).isEqualTo(earlierTurns.path(m));
            }
            String firstTurn = earlierTurns.path(0).toString() + earlierTurns.path(1).toString();
            assertThat(firstTurn).as("the shown image travels in its own message").contains("Research the market for widgets.");
            assertThat(firstTurn).contains("base64");
        }
    }

    private static ToolDefinition tool(String name) {
        return ToolDefinition.builder().name(name).description("tool " + name)
                .parameters(Map.of("type", "object", "properties", Map.of("q", Map.of("type", "string")))).build();
    }

    /**
     * OpenAI's input with its cache breakpoint removed, and the single text block that carried it
     * folded back to the plain string sent when the turn isn't marked: the same tokens, so the
     * marked turn still matches the next request's unmarked copy of it.
     */
    private static JsonNode withoutBreakpoints(JsonNode input) {
        ArrayNode items = (ArrayNode) input.deepCopy();
        for (JsonNode node : items) {
            ObjectNode item = (ObjectNode) node;
            for (String field : List.of("content", "output")) {
                JsonNode blocks = item.path(field);
                if (!blocks.isArray()) {
                    continue;
                }
                for (JsonNode block : blocks) {
                    ((ObjectNode) block).remove("prompt_cache_breakpoint");
                }
                if (blocks.size() == 1 && "input_text".equals(blocks.path(0).path("type").asText())) {
                    item.put(field, blocks.path(0).path("text").asText());
                }
            }
        }
        return items;
    }

    private static JsonNode parse(String body) {
        return JSON.readTree(body);
    }
}
