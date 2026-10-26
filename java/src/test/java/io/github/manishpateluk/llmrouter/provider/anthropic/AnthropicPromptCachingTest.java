package io.github.manishpateluk.llmrouter.provider.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.CacheCreation;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Usage;
import com.anthropic.services.blocking.MessageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.manishpateluk.llmrouter.config.PromptCacheTtl;
import io.github.manishpateluk.llmrouter.config.RouterConfig;
import io.github.manishpateluk.llmrouter.model.Attachment;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.model.Role;
import io.github.manishpateluk.llmrouter.model.ToolCall;
import io.github.manishpateluk.llmrouter.model.ToolDefinition;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Where {@link AnthropicAdapter} puts {@code cache_control} breakpoints (§4.3), and how it reports cached tokens. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnthropicPromptCachingTest {

    private static final String MODEL = "claude-opus-5-5";

    @Mock
    private AnthropicClient client;
    @Mock
    private MessageService messageService;

    private final ArgumentCaptor<MessageCreateParams> sent = ArgumentCaptor.forClass(MessageCreateParams.class);

    @BeforeEach
    void stubClient() {
        when(client.messages()).thenReturn(messageService);
        when(messageService.create(sent.capture())).thenReturn(reply(10, 5, 0, 0, 0));
        when(client.files()).thenThrow(new IllegalStateException("no Files API in this test"));
    }

    @Test
    void marksTheToolsTheSystemInstructionsAndTheLastHistoryMessageButNeverThePrompt() {
        send(Request.builder()
                .systemInstructions("You are a careful assistant.")
                .tools(List.of(tool("a"), tool("b")))
                .history(List.of(message(Role.USER, "first"), message(Role.ASSISTANT, "reply")))
                .prompt("second"));

        JsonNode body = body();
        assertThat(body.path("tools").path(0).has("cache_control")).isFalse();
        assertThat(body.path("tools").path(1).has("cache_control")).isTrue();
        assertThat(body.path("system").path(0).has("cache_control")).isTrue();
        JsonNode messages = body.path("messages");
        JsonNode reply = messages.path(1).path("content");
        assertThat(reply.path(reply.size() - 1).path("text").asText()).isEqualTo("reply");
        assertThat(reply.path(reply.size() - 1).has("cache_control")).isTrue();
        JsonNode prompt = messages.path(messages.size() - 1);
        assertThat(prompt.path("content").asText()).as("the prompt goes as plain text, unmarked").isEqualTo("second");
        assertThat(cacheMarkers(body)).isEqualTo(3);
    }

    @Test
    void consecutiveStepsOfAToolLoopExtendTheSamePrefix() {
        List<io.github.manishpateluk.llmrouter.model.Message> history = new ArrayList<>(List.of(
                io.github.manishpateluk.llmrouter.model.Message.user("Research the market for widgets.")));
        Request.RequestBuilder step = Request.builder()
                .systemInstructions("You are a researcher.")
                .tools(List.of(tool("search"), tool("fetch")))
                .prompt("Continue toward the goal above.");
        List<JsonNode> bodies = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            send(step.history(List.copyOf(history)));
            bodies.add(body());
            ToolCall call = ToolCall.builder().id("call_" + i).name("search").arguments(Map.of("q", "widgets " + i)).build();
            history.add(io.github.manishpateluk.llmrouter.model.Message.assistant(i % 2 == 0 ? "" : "Searching.", List.of(call)));
            history.add(io.github.manishpateluk.llmrouter.model.Message.tool("call_" + i, "result " + i));
        }

        for (int i = 0; i + 1 < bodies.size(); i++) {
            JsonNode earlier = withoutMarkers(bodies.get(i));
            JsonNode later = withoutMarkers(bodies.get(i + 1));
            assertThat(later.path("tools")).isEqualTo(earlier.path("tools"));
            assertThat(later.path("system")).isEqualTo(earlier.path("system"));
            JsonNode earlierMessages = earlier.path("messages");
            int conversation = earlierMessages.size() - 1; // all but the prompt
            for (int m = 0; m < conversation; m++) {
                assertThat(later.path("messages").path(m)).as("step %d message %d", i + 1, m).isEqualTo(earlierMessages.path(m));
            }
            // the earlier call's conversation marker sits on a block the later call sends again unchanged
            JsonNode marked = bodies.get(i).path("messages").path(conversation - 1).path("content");
            assertThat(marked.path(marked.size() - 1).has("cache_control")).isTrue();
        }
    }

    @Test
    void theConversationMarkerGoesOnTheLastBlockWhateverTheLastMessageIs() {
        ToolCall call = ToolCall.builder().id("c1").name("search").arguments(Map.of("q", "x")).build();
        Map<String, List<io.github.manishpateluk.llmrouter.model.Message>> shapes = Map.of(
                "tool_result", List.of(
                        io.github.manishpateluk.llmrouter.model.Message.assistant("", List.of(call)),
                        io.github.manishpateluk.llmrouter.model.Message.tool("c1", "found it")),
                "tool_use", List.of(
                        io.github.manishpateluk.llmrouter.model.Message.user("go"),
                        io.github.manishpateluk.llmrouter.model.Message.assistant("Looking.", List.of(call))),
                "text", List.of(io.github.manishpateluk.llmrouter.model.Message.user("a question")),
                "image", List.of(io.github.manishpateluk.llmrouter.model.Message.user("", List.of(imageAttachment()))),
                "legacy tool text", List.of(io.github.manishpateluk.llmrouter.model.Message.tool("flattened")));
        shapes.forEach((expectedType, history) -> {
            send(Request.builder().history(history).prompt("next"));
            JsonNode messages = body().path("messages");
            JsonNode content = messages.path(messages.size() - 2).path("content");
            JsonNode last = content.path(content.size() - 1);
            assertThat(last.path("type").asText()).as(expectedType)
                    .isEqualTo(expectedType.equals("legacy tool text") ? "text" : expectedType);
            assertThat(last.has("cache_control")).as(expectedType).isTrue();
            assertThat(cacheMarkers(body())).as(expectedType).isEqualTo(1);
        });
    }

    @Test
    void historySystemTurnsAreASecondSystemBlockWithItsOwnMarker() {
        Request.RequestBuilder request = Request.builder()
                .systemInstructions("Stable instructions.")
                .history(List.of(message(Role.SYSTEM, "Summary one."), message(Role.USER, "hello")))
                .prompt("next");
        send(request);
        JsonNode first = body();
        send(request.history(List.of(message(Role.SYSTEM, "Summary two."), message(Role.USER, "hello"))));
        JsonNode second = body();

        assertThat(first.path("system")).hasSize(2);
        assertThat(first.path("system").path(1).path("text").asText()).isEqualTo("Summary one.");
        assertThat(first.path("system").path(0).has("cache_control")).isTrue();
        assertThat(first.path("system").path(1).has("cache_control")).isTrue();
        assertThat(second.path("system").path(0)).as("the instructions block doesn't change with the summary")
                .isEqualTo(first.path("system").path(0));
    }

    @Test
    void aHistoryOfOnlySystemTurnsMarksTheAttachmentsInstead() {
        send(Request.builder()
                .history(List.of(message(Role.SYSTEM, "note")))
                .attachments(List.of(textAttachment("doc")))
                .prompt("read it"));

        JsonNode content = body().path("messages").path(0).path("content");
        assertThat(content.path(0).has("cache_control")).isTrue();
        assertThat(content.path(1).has("cache_control")).isFalse();
    }

    @Test
    void withoutSystemInstructionsCachesThroughTheLastTool() {
        send(Request.builder().tools(List.of(tool("a"), tool("b"))).prompt("hi"));

        JsonNode body = body();
        assertThat(body.path("tools").path(0).has("cache_control")).isFalse();
        assertThat(body.path("tools").path(1).has("cache_control")).isTrue();
        assertThat(cacheMarkers(body)).isEqualTo(1);
    }

    @Test
    void withoutHistoryCachesThroughTheLastAttachmentOnly() {
        List<Attachment> attachments = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            attachments.add(textAttachment("doc " + i));
        }
        send(Request.builder().attachments(attachments).prompt("summarise these"));

        JsonNode content = body().path("messages").path(0).path("content");
        assertThat(content.size()).isEqualTo(6);
        assertThat(content.path(4).has("cache_control")).isTrue();
        assertThat(content.path(5).has("cache_control")).as("the prompt itself").isFalse();
        assertThat(cacheMarkers(body())).as("one breakpoint, not one per attachment (Anthropic allows four)").isEqualTo(1);
    }

    @Test
    void neverMoreThanFourMarkers() {
        for (int mask = 0; mask < 32; mask++) {
            Request.RequestBuilder request = Request.builder().prompt("go");
            if ((mask & 1) != 0) {
                request.tools(List.of(tool("a"), tool("b")));
            }
            if ((mask & 2) != 0) {
                request.systemInstructions("instructions");
            }
            List<io.github.manishpateluk.llmrouter.model.Message> history = new ArrayList<>();
            if ((mask & 4) != 0) {
                history.add(message(Role.SYSTEM, "summary"));
            }
            if ((mask & 8) != 0) {
                history.add(io.github.manishpateluk.llmrouter.model.Message.user("seen", List.of(textAttachment("a"), textAttachment("b"))));
                history.add(message(Role.ASSISTANT, "ok"));
            }
            request.history(history);
            for (int attachments = 0; attachments <= 6; attachments += 3) {
                List<Attachment> files = new ArrayList<>();
                for (int i = 0; i < attachments; i++) {
                    files.add(textAttachment("f" + i));
                }
                send(request.attachments(files).config((mask & 16) != 0
                        ? RouterConfig.builder().promptCacheTtl(PromptCacheTtl.LONG).build() : null));
                assertThat(cacheMarkers(body())).as("mask %d, %d attachments", mask, attachments).isBetween(0, 4);
            }
        }
    }

    @Test
    void historyAttachmentsGoInTheirOwnMessageBeforeItsText() {
        send(Request.builder()
                .history(List.of(
                        io.github.manishpateluk.llmrouter.model.Message.user("Here's the deck.", List.of(textAttachment("slides"))),
                        message(Role.ASSISTANT, "Got it.")))
                .prompt("What's on slide 2?"));

        JsonNode first = body().path("messages").path(0).path("content");
        assertThat(first.path(0).path("type").asText()).isEqualTo("document");
        assertThat(first.path(1).path("text").asText()).isEqualTo("Here's the deck.");
        assertThat(body().path("messages").path(2).path("content").asText()).isEqualTo("What's on slide 2?");
    }

    @Test
    void theLongTtlPutsAnHourOnEveryMarker() {
        send(Request.builder()
                .config(RouterConfig.builder().promptCacheTtl(PromptCacheTtl.LONG).build())
                .systemInstructions("system")
                .tools(List.of(tool("a")))
                .history(List.of(message(Role.USER, "first"), message(Role.ASSISTANT, "reply")))
                .prompt("second"));

        List<JsonNode> markers = new ArrayList<>();
        collectMarkers(body(), markers);
        assertThat(markers).hasSize(3).allSatisfy(marker -> assertThat(marker.path("ttl").asText()).isEqualTo("1h"));
    }

    @Test
    void theDefaultTtlSendsNoTtl() {
        send(Request.builder().systemInstructions("system").prompt("hi"));

        List<JsonNode> markers = new ArrayList<>();
        collectMarkers(body(), markers);
        assertThat(markers).singleElement().satisfies(marker -> assertThat(marker.has("ttl")).isFalse());
    }

    @Test
    void aBarePromptIsSentAsBeforeWithNothingToCache() {
        send(Request.builder().prompt("hi"));

        assertThat(body().path("messages").path(0).path("content").asText()).isEqualTo("hi");
        assertThat(cacheMarkers(body())).isZero();
    }

    @Test
    void promptCachingOffSendsNoMarkersAnywhere() {
        send(Request.builder()
                .config(RouterConfig.builder().promptCaching(false).build())
                .systemInstructions("system")
                .tools(List.of(tool("a")))
                .attachments(List.of(textAttachment("doc")))
                .history(List.of(message(Role.SYSTEM, "summary"),
                        io.github.manishpateluk.llmrouter.model.Message.user("first", List.of(textAttachment("seen"))),
                        message(Role.ASSISTANT, "reply")))
                .prompt("second"));

        assertThat(cacheMarkers(body())).isZero();
    }

    @Test
    void inputTokensIncludeCachedTokensAndTheCacheCountsAreReported() {
        when(messageService.create(sent.capture())).thenReturn(reply(100, 20, 3000, 500, 0));

        Response response = send(Request.builder().prompt("hi"));

        assertThat(response.getUsage().getInputTokens()).isEqualTo(3600);
        assertThat(response.getUsage().getCacheReadTokens()).isEqualTo(3000);
        assertThat(response.getUsage().getCacheWriteTokens()).isEqualTo(500);
        assertThat(response.getUsage().getCacheWriteLongTtlTokens()).isZero();
        assertThat(response.getUsage().getOutputTokens()).isEqualTo(20);
    }

    @Test
    void oneHourWritesAreReportedAsThePartOfTheWritesWithTheLongTtl() {
        when(messageService.create(sent.capture())).thenReturn(reply(100, 20, 0, 500, 300));

        Response response = send(Request.builder().prompt("hi"));

        assertThat(response.getUsage().getCacheWriteTokens()).isEqualTo(500);
        assertThat(response.getUsage().getCacheWriteLongTtlTokens()).isEqualTo(300);
    }

    private Response send(Request.RequestBuilder request) {
        return new AnthropicAdapter(client).send(MODEL, request.build());
    }

    private JsonNode body() {
        return ObjectMappers.jsonMapper().valueToTree(sent.getValue()._body());
    }

    private static int cacheMarkers(JsonNode node) {
        List<JsonNode> markers = new ArrayList<>();
        collectMarkers(node, markers);
        return markers.size();
    }

    private static void collectMarkers(JsonNode node, List<JsonNode> markers) {
        if (node.has("cache_control")) {
            markers.add(node.get("cache_control"));
        }
        for (JsonNode child : node) {
            collectMarkers(child, markers);
        }
    }

    /** {@code node} with every {@code cache_control} removed, and plain-string content as the one text block it stands for. */
    private static JsonNode withoutMarkers(JsonNode node) {
        JsonNode copy = node.deepCopy();
        strip(copy);
        return copy;
    }

    private static void strip(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.remove("cache_control");
            if (object.has("role") && object.path("content").isTextual()) {
                ArrayNode blocks = object.arrayNode();
                blocks.addObject().put("type", "text").put("text", object.path("content").asText());
                object.set("content", blocks);
            }
        }
        for (JsonNode child : node) {
            strip(child);
        }
    }

    private static Attachment textAttachment(String text) {
        return Attachment.builder().mediaType("text/plain").data(text.getBytes(StandardCharsets.UTF_8)).build();
    }

    private static Attachment imageAttachment() {
        return Attachment.builder().mediaType("image/png").data(new byte[] {(byte) 0x89, 'P', 'N', 'G'}).build();
    }

    private static ToolDefinition tool(String name) {
        return ToolDefinition.builder().name(name).description("tool " + name)
                .parameters(Map.of("type", "object", "properties", Map.of())).build();
    }

    private static io.github.manishpateluk.llmrouter.model.Message message(Role role, String content) {
        return io.github.manishpateluk.llmrouter.model.Message.builder().role(role).content(content).build();
    }

    private static Message reply(long input, long output, long cacheRead, long cacheWrite, long cacheWriteOneHour) {
        return Message.builder()
                .id("msg_1")
                .model(MODEL)
                .container(Optional.empty())
                .stopDetails(Optional.empty())
                .stopReason(Optional.empty())
                .stopSequence(Optional.empty())
                .content(List.of(ContentBlock.ofText(TextBlock.builder().text("ok").citations(List.of()).build())))
                .usage(Usage.builder()
                        .inputTokens(input)
                        .outputTokens(output)
                        .cacheCreation(cacheWriteOneHour == 0 ? Optional.empty() : Optional.of(CacheCreation.builder()
                                .ephemeral1hInputTokens(cacheWriteOneHour)
                                .ephemeral5mInputTokens(cacheWrite - cacheWriteOneHour)
                                .build()))
                        .cacheCreationInputTokens(cacheWrite)
                        .cacheReadInputTokens(cacheRead)
                        .inferenceGeo(Optional.empty())
                        .outputTokensDetails(Optional.empty())
                        .serverToolUse(Optional.empty())
                        .serviceTier(Optional.empty())
                        .build())
                .build();
    }
}
