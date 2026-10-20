package io.github.manishpateluk.llmrouter.provider.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.AnthropicClientAsync;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.Headers;
import com.anthropic.errors.BadRequestException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Usage;
import com.anthropic.services.async.MessageServiceAsync;
import com.anthropic.services.blocking.MessageService;
import io.github.manishpateluk.llmrouter.LlmRouter;
import io.github.manishpateluk.llmrouter.config.RouteEntry;
import io.github.manishpateluk.llmrouter.config.RouterConfig;
import io.github.manishpateluk.llmrouter.config.StructuredOutputStrategy;
import io.github.manishpateluk.llmrouter.error.RouterExhaustedException;
import io.github.manishpateluk.llmrouter.model.AttemptOutcome;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.provider.Provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Anthropic rejecting a schema as too large for its grammar: see the {@link AnthropicAdapter} javadoc. */
@ExtendWith(MockitoExtension.class)
class AnthropicGrammarFallbackTest {

    private static final String MODEL = "claude-sonnet-5-5";
    private static final String ANSWER = "{\"name\":\"Ada\",\"tags\":[\"math\"]}";

    @Mock
    private AnthropicClient client;
    @Mock
    private MessageService messageService;
    @Mock
    private AnthropicClientAsync asyncClient;
    @Mock
    private MessageServiceAsync asyncMessageService;

    @Test
    void retriesOnceWithTheSchemaInThePromptAndNoOutputConfig() {
        when(client.messages()).thenReturn(messageService);
        ArgumentCaptor<MessageCreateParams> sent = ArgumentCaptor.forClass(MessageCreateParams.class);
        when(messageService.create(sent.capture())).thenThrow(grammarTooLarge()).thenReturn(textMessage(ANSWER));

        Response response = new AnthropicAdapter(client).send(MODEL, request(StructuredOutputStrategy.AUTO));

        assertThat(response.getContent()).isEqualTo(ANSWER);
        assertThat(sent.getAllValues()).hasSize(2);
        assertThat(sent.getAllValues().get(0).outputConfig()).isPresent();
        MessageCreateParams retry = sent.getAllValues().get(1);
        assertThat(retry.model().toString()).isEqualTo(MODEL);
        assertThat(retry.outputConfig()).isEmpty();
        assertThat(retry.system().orElseThrow().asTextBlockParams().get(0).text())
                .startsWith("Be brief.")
                .contains("Respond with valid JSON only", "\"tags\"");
    }

    @Test
    void laterCallsWithTheSameSchemaGoStraightToThePrompt() {
        when(client.messages()).thenReturn(messageService);
        ArgumentCaptor<MessageCreateParams> sent = ArgumentCaptor.forClass(MessageCreateParams.class);
        when(messageService.create(sent.capture())).thenThrow(grammarTooLarge()).thenReturn(textMessage(ANSWER));
        AnthropicAdapter adapter = new AnthropicAdapter(client);

        adapter.send(MODEL, request(StructuredOutputStrategy.PROMPT_FALLBACK));
        Response second = adapter.send(MODEL, request(StructuredOutputStrategy.AUTO).toBuilder().prompt("Another").build());

        assertThat(second.getContent()).isEqualTo(ANSWER);
        assertThat(sent.getAllValues()).hasSize(3);
        assertThat(sent.getAllValues().get(2).outputConfig()).isEmpty();
    }

    @Test
    void theSameSchemaWithKeysInAnotherOrderCountsAsTheSame() {
        when(client.messages()).thenReturn(messageService);
        ArgumentCaptor<MessageCreateParams> sent = ArgumentCaptor.forClass(MessageCreateParams.class);
        when(messageService.create(sent.capture())).thenThrow(grammarTooLarge()).thenReturn(textMessage(ANSWER));
        AnthropicAdapter adapter = new AnthropicAdapter(client);
        Map<String, Object> reordered = new LinkedHashMap<>();
        schema().entrySet().stream().toList().reversed().forEach(e -> reordered.put(e.getKey(), e.getValue()));

        adapter.send(MODEL, request(StructuredOutputStrategy.AUTO));
        adapter.send(MODEL, request(StructuredOutputStrategy.AUTO).toBuilder().responseSchema(reordered).build());

        assertThat(sent.getAllValues()).hasSize(3);
        assertThat(sent.getAllValues().get(2).outputConfig()).isEmpty();
    }

    @Test
    void anotherModelStillTriesTheSchemaNatively() {
        when(client.messages()).thenReturn(messageService);
        ArgumentCaptor<MessageCreateParams> sent = ArgumentCaptor.forClass(MessageCreateParams.class);
        when(messageService.create(sent.capture()))
                .thenThrow(grammarTooLarge()).thenReturn(textMessage(ANSWER)).thenReturn(textMessage(ANSWER));
        AnthropicAdapter adapter = new AnthropicAdapter(client);

        adapter.send(MODEL, request(StructuredOutputStrategy.AUTO));
        adapter.send("claude-opus-5-5", request(StructuredOutputStrategy.AUTO));

        assertThat(sent.getAllValues().get(2).outputConfig()).isPresent();
    }

    @Test
    void nativeStrategyDoesNotRetryAndKeepsSendingTheSchemaNatively() {
        when(client.messages()).thenReturn(messageService);
        ArgumentCaptor<MessageCreateParams> sent = ArgumentCaptor.forClass(MessageCreateParams.class);
        when(messageService.create(sent.capture())).thenThrow(grammarTooLarge());
        AnthropicAdapter adapter = new AnthropicAdapter(client);

        assertThatThrownBy(() -> adapter.send(MODEL, request(StructuredOutputStrategy.NATIVE)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> adapter.send(MODEL, request(StructuredOutputStrategy.NATIVE)))
                .isInstanceOf(BadRequestException.class);

        assertThat(sent.getAllValues()).hasSize(2).allSatisfy(params -> assertThat(params.outputConfig()).isPresent());
    }

    @Test
    void anyOtherBadRequestIsThrownUnchanged() {
        when(client.messages()).thenReturn(messageService);
        BadRequestException other = badRequest("invalid_request_error", "max_tokens: must be at least 1");
        when(messageService.create(any(MessageCreateParams.class))).thenThrow(other);

        assertThatThrownBy(() -> new AnthropicAdapter(client).send(MODEL, request(StructuredOutputStrategy.AUTO)))
                .isSameAs(other);
        verify(messageService, times(1)).create(any(MessageCreateParams.class));
    }

    @Test
    void aReplyThatIsntJsonFailsTheAttempt() {
        when(client.messages()).thenReturn(messageService);
        when(messageService.create(any(MessageCreateParams.class)))
                .thenThrow(grammarTooLarge()).thenReturn(textMessage("Sure! Here is the JSON you asked for."));

        assertThatThrownBy(() -> new AnthropicAdapter(client).send(MODEL, request(StructuredOutputStrategy.AUTO)))
                .hasMessageStartingWith("Structured output failed")
                .hasMessageContaining("isn't valid JSON");
    }

    @Test
    void aReplyThatDoesntMatchTheSchemaFailsTheAttempt() {
        when(client.messages()).thenReturn(messageService);
        when(messageService.create(any(MessageCreateParams.class)))
                .thenThrow(grammarTooLarge()).thenReturn(textMessage("{\"name\":\"Ada\",\"tags\":\"math\"}"));

        assertThatThrownBy(() -> new AnthropicAdapter(client).send(MODEL, request(StructuredOutputStrategy.AUTO)))
                .hasMessageStartingWith("Structured output failed")
                .hasMessageContaining("$.tags should be array");
    }

    @Test
    void sendAsyncRetriesTheSameWay() {
        when(client.async()).thenReturn(asyncClient);
        when(asyncClient.messages()).thenReturn(asyncMessageService);
        ArgumentCaptor<MessageCreateParams> sent = ArgumentCaptor.forClass(MessageCreateParams.class);
        when(asyncMessageService.create(sent.capture()))
                .thenReturn(CompletableFuture.failedFuture(grammarTooLarge()))
                .thenReturn(CompletableFuture.completedFuture(textMessage(ANSWER)));

        Response response = new AnthropicAdapter(client).sendAsync(MODEL, request(StructuredOutputStrategy.AUTO)).join();

        assertThat(response.getContent()).isEqualTo(ANSWER);
        assertThat(sent.getAllValues().get(1).outputConfig()).isEmpty();
    }

    @Test
    void theRouterReturnsTheParsedAnswerFromAnthropic() {
        when(client.messages()).thenReturn(messageService);
        when(messageService.create(any(MessageCreateParams.class))).thenThrow(grammarTooLarge()).thenReturn(textMessage(ANSWER));
        LlmRouter router = new LlmRouter(List.of(new AnthropicAdapter(client)));

        Response response = router.complete(request(StructuredOutputStrategy.AUTO).toBuilder()
                .config(RouterConfig.builder().route(List.of(RouteEntry.of(Provider.ANTHROPIC, MODEL))).build())
                .build());

        assertThat(response.getProviderUsed()).isEqualTo(Provider.ANTHROPIC);
        assertThat(response.getStructuredOutput()).containsEntry("name", "Ada").containsEntry("tags", List.of("math"));
        assertThat(response.getDroppedFeatures()).isEmpty();
        assertThat(response.getAttempts()).isEmpty();
    }

    @Test
    void theRouterFallsBackWhenThePromptReplyDoesntFit() {
        when(client.messages()).thenReturn(messageService);
        when(messageService.create(any(MessageCreateParams.class))).thenThrow(grammarTooLarge()).thenReturn(textMessage("{}"));
        LlmRouter router = new LlmRouter(List.of(new AnthropicAdapter(client)));

        assertThatThrownBy(() -> router.complete(request(StructuredOutputStrategy.AUTO).toBuilder()
                .config(RouterConfig.builder().route(List.of(RouteEntry.of(Provider.ANTHROPIC, MODEL))).build())
                .build()))
                .isInstanceOfSatisfying(RouterExhaustedException.class, e -> assertThat(e.attempts())
                        .singleElement()
                        .satisfies(attempt -> {
                            assertThat(attempt.getOutcome()).isEqualTo(AttemptOutcome.FAILED);
                            assertThat(attempt.getReason()).contains("Structured output failed", "$.name is missing");
                        }));
    }

    private static Request request(StructuredOutputStrategy strategy) {
        return Request.builder()
                .prompt("Describe Ada Lovelace")
                .systemInstructions("Be brief.")
                .responseSchema(schema())
                .config(RouterConfig.builder().structuredOutputStrategy(strategy).build())
                .build();
    }

    private static Map<String, Object> schema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "name", Map.of("type", "string"),
                "tags", Map.of("type", "array", "items", Map.of("type", "string"))));
        schema.put("required", List.of("name", "tags"));
        return schema;
    }

    private static BadRequestException grammarTooLarge() {
        return badRequest("invalid_request_error", "The compiled grammar is too large, which would cause performance issues. "
                + "Simplify your tool schemas or reduce the number of strict tools.");
    }

    private static BadRequestException badRequest(String type, String message) {
        return BadRequestException.builder()
                .headers(Headers.builder().build())
                .body(JsonValue.from(Map.of("type", "error", "error", Map.of("type", type, "message", message))))
                .build();
    }

    private static Message textMessage(String text) {
        return Message.builder()
                .id("msg_1")
                .model(MODEL)
                .container(java.util.Optional.empty())
                .stopDetails(java.util.Optional.empty())
                .stopReason(java.util.Optional.empty())
                .stopSequence(java.util.Optional.empty())
                .content(List.of(ContentBlock.ofText(TextBlock.builder().text(text).citations(List.of()).build())))
                .usage(Usage.builder()
                        .inputTokens(10)
                        .outputTokens(5)
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
                        .build())
                .build();
    }
}
