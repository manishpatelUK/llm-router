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
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Usage;
import com.anthropic.services.blocking.MessageService;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.manishpateluk.llmrouter.config.RouterConfig;
import io.github.manishpateluk.llmrouter.model.Attachment;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.model.Role;
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
        when(messageService.create(sent.capture())).thenReturn(reply(10, 5, 0, 0));
        when(client.files()).thenThrow(new IllegalStateException("no Files API in this test"));
    }

    @Test
    void cachesTheSystemInstructionsAndTheConversationSoFar() {
        send(Request.builder()
                .systemInstructions("You are a careful assistant.")
                .tools(List.of(tool("a"), tool("b")))
                .history(List.of(message(Role.USER, "first"), message(Role.ASSISTANT, "reply")))
                .prompt("second"));

        JsonNode body = body();
        assertThat(body.path("system").path(0).has("cache_control")).isTrue();
        assertThat(body.path("tools").path(1).has("cache_control")).as("the system breakpoint covers the tools").isFalse();
        JsonNode messages = body.path("messages");
        JsonNode last = messages.path(messages.size() - 1).path("content");
        assertThat(last.path(last.size() - 1).path("text").asText()).isEqualTo("second");
        assertThat(last.path(last.size() - 1).has("cache_control")).isTrue();
        assertThat(cacheMarkers(body)).isEqualTo(2);
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
                .history(List.of(message(Role.USER, "first"), message(Role.ASSISTANT, "reply")))
                .prompt("second"));

        assertThat(cacheMarkers(body())).isZero();
    }

    @Test
    void inputTokensIncludeCachedTokensAndTheCacheCountsAreReported() {
        when(messageService.create(sent.capture())).thenReturn(reply(100, 20, 3000, 500));

        Response response = send(Request.builder().prompt("hi"));

        assertThat(response.getUsage().getInputTokens()).isEqualTo(3600);
        assertThat(response.getUsage().getCacheReadTokens()).isEqualTo(3000);
        assertThat(response.getUsage().getCacheWriteTokens()).isEqualTo(500);
        assertThat(response.getUsage().getOutputTokens()).isEqualTo(20);
    }

    private Response send(Request.RequestBuilder request) {
        return new AnthropicAdapter(client).send(MODEL, request.build());
    }

    private JsonNode body() {
        return ObjectMappers.jsonMapper().valueToTree(sent.getValue()._body());
    }

    private static int cacheMarkers(JsonNode node) {
        int count = node.has("cache_control") ? 1 : 0;
        for (JsonNode child : node) {
            count += cacheMarkers(child);
        }
        return count;
    }

    private static Attachment textAttachment(String text) {
        return Attachment.builder().mediaType("text/plain").data(text.getBytes(StandardCharsets.UTF_8)).build();
    }

    private static ToolDefinition tool(String name) {
        return ToolDefinition.builder().name(name).description("tool " + name)
                .parameters(Map.of("type", "object", "properties", Map.of())).build();
    }

    private static io.github.manishpateluk.llmrouter.model.Message message(Role role, String content) {
        return io.github.manishpateluk.llmrouter.model.Message.builder().role(role).content(content).build();
    }

    private static Message reply(long input, long output, long cacheRead, long cacheWrite) {
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
                        .cacheCreation(Optional.empty())
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
