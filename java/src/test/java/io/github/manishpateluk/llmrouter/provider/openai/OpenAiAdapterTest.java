package io.github.manishpateluk.llmrouter.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import io.github.manishpateluk.llmrouter.model.Attachment;
import io.github.manishpateluk.llmrouter.model.Message;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.model.ToolCall;
import io.github.manishpateluk.llmrouter.model.ToolDefinition;
import io.github.manishpateluk.llmrouter.provider.Provider;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.core.JsonValue;
import com.openai.core.ObjectMappers;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.files.FileCreateParams;
import com.openai.models.files.FileObject;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputContent;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseInputTextContent;
import com.openai.services.async.ResponseServiceAsync;
import com.openai.services.blocking.FileService;
import com.openai.services.blocking.ResponseService;

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
    private ResponseService responseService;
    @Mock
    private FileService fileService;
    @Mock
    private OpenAIClientAsync asyncClient;
    @Mock
    private ResponseServiceAsync asyncResponseService;

    @Test
    void idAndAvailability() {
        assertThat(new OpenAiAdapter(client).id()).isEqualTo(Provider.OPENAI);
        assertThat(new OpenAiAdapter(client).isAvailable()).isTrue();
        assertThat(new OpenAiAdapter((String) null).isAvailable()).isFalse();
        assertThat(new OpenAiAdapter("").isAvailable()).isFalse();
    }

    @Test
    void sendMapsRequestAndParsesTextResponse() {
        ArgumentCaptor<ResponseCreateParams> captor = capture(textResponse("Hello there"));

        Response response = new OpenAiAdapter(client).send("gpt-6-astra", Request.builder().prompt("Hi").systemInstructions("Be terse.").build());

        assertThat(response.getContent()).isEqualTo("Hello there");
        assertThat(response.getToolCalls()).isEmpty();
        assertThat(response.getUsage().getInputTokens()).isEqualTo(10);
        assertThat(response.getUsage().getOutputTokens()).isEqualTo(5);
        assertThat(response.getOriginal()).isInstanceOf(com.openai.models.responses.Response.class);
        ResponseCreateParams sent = captor.getValue();
        assertThat(sent.model().orElseThrow().asString()).isEqualTo("gpt-6-astra");
        assertThat(sent.instructions()).contains("Be terse.");
        assertThat(sent.store()).as("OpenAI keeps no copy of the response").contains(false);
        assertThat(sent.previousResponseId()).as("every request is stateless").isEmpty();
        assertThat(input(sent)).singleElement().satisfies(item ->
                assertThat(item.asEasyInputMessage().content().asTextInput()).isEqualTo("Hi"));
    }

    @Test
    void sendIncludesTemperatureAndTopPWhenPresent() {
        ArgumentCaptor<ResponseCreateParams> captor = capture(textResponse("ok"));

        new OpenAiAdapter(client).send("gpt-6-astra", Request.builder().prompt("hi").temperature(0.7).topP(0.9).build());

        assertThat(captor.getValue().temperature()).isEqualTo(Optional.of(0.7));
        assertThat(captor.getValue().topP()).isEqualTo(Optional.of(0.9));
    }

    @Test
    void sendOmitsTemperatureAndTopPWhenAbsent() {
        ArgumentCaptor<ResponseCreateParams> captor = capture(textResponse("ok"));

        new OpenAiAdapter(client).send("gpt-6-astra", Request.builder().prompt("hi").build());

        assertThat(captor.getValue().temperature()).isEmpty();
        assertThat(captor.getValue().topP()).isEmpty();
    }

    @Test
    void sendMapsFunctionToolCallToToolCall() {
        capture(response("""
                [{"type": "reasoning", "id": "rs_1", "summary": []},
                 {"type": "function_call", "id": "fc_1", "call_id": "call_1", "name": "lookup", "arguments": "{\\"query\\":\\"weather\\"}"}]
                """, USAGE));

        Response response = new OpenAiAdapter(client).send("gpt-6-astra", Request.builder().prompt("lookup something").build());

        assertThat(response.getToolCalls()).singleElement().satisfies(call -> {
            assertThat(call.getId()).as("the call_id, which the tool result must quote").isEqualTo("call_1");
            assertThat(call.getName()).isEqualTo("lookup");
            assertThat(call.getArguments()).containsEntry("query", "weather");
        });
        assertThat(response.getContent()).isEmpty();
    }

    @Test
    void aFunctionCallWithNoArgumentsGetsAnEmptyMap() {
        capture(response("""
                [{"type": "function_call", "call_id": "call_1", "name": "now", "arguments": ""}]
                """, USAGE));

        Response response = new OpenAiAdapter(client).send("gpt-6-astra", Request.builder().prompt("time?").build());

        assertThat(response.getToolCalls()).singleElement().satisfies(call -> assertThat(call.getArguments()).isEmpty());
    }

    @Test
    void theTextOfEveryOutputMessageIsJoinedAndReasoningItemsAreSkipped() {
        capture(response("""
                [{"type": "reasoning", "id": "rs_1", "summary": [{"type": "summary_text", "text": "thinking"}]},
                 {"type": "message", "id": "m1", "role": "assistant", "status": "completed",
                  "content": [{"type": "output_text", "text": "Hello", "annotations": []},
                              {"type": "output_text", "text": " there", "annotations": []}]},
                 {"type": "message", "id": "m2", "role": "assistant", "status": "completed",
                  "content": [{"type": "output_text", "text": "!", "annotations": []}]}]
                """, USAGE));

        Response response = new OpenAiAdapter(client).send("gpt-6-astra", Request.builder().prompt("hi").build());

        assertThat(response.getContent()).isEqualTo("Hello there!");
    }

    @Test
    void toolsAndAReasoningEffortTravelTogetherAndToolsAreNotStrict() {
        ArgumentCaptor<ResponseCreateParams> captor = capture(textResponse("ok"));

        new OpenAiAdapter(client).send("gpt-6-luna", Request.builder().prompt("Weather?")
                .reasoningEffort("low")
                .tools(List.of(ToolDefinition.builder().name("lookup").description("Looks things up.")
                        .parameters(Map.of("type", "object", "properties", Map.of("q", Map.of("type", "string"))))
                        .build()))
                .build());

        ResponseCreateParams sent = captor.getValue();
        assertThat(sent.reasoning().flatMap(Reasoning::effort)).contains(ReasoningEffort.LOW);
        FunctionTool tool = sent.tools().orElseThrow().get(0).asFunction();
        assertThat(tool.name()).isEqualTo("lookup");
        assertThat(tool.description()).contains("Looks things up.");
        assertThat(tool.strict()).as("strict would demand every property be required").contains(false);
        assertThat(tool.parameters().orElseThrow()._additionalProperties()).containsKeys("type", "properties");
    }

    @Test
    void sendUploadsDocumentAttachmentOnceAndReusesFileIdOnByteIdenticalRepeat() {
        ArgumentCaptor<ResponseCreateParams> captor = capture(textResponse("ok"));
        when(client.files()).thenReturn(fileService);
        when(fileService.create(any(FileCreateParams.class))).thenReturn(fixtureFileObject("file_abc"));

        OpenAiAdapter adapter = new OpenAiAdapter(client);
        Attachment attachment = Attachment.builder().mediaType("application/pdf").data(new byte[]{1, 2, 3}).build();
        Request request = Request.builder().prompt("summarize").attachments(List.of(attachment)).build();

        adapter.send("gpt-6-astra", request);
        adapter.send("gpt-6-astra", request);

        verify(fileService, times(1)).create(any(FileCreateParams.class));
        for (ResponseCreateParams sent : captor.getAllValues()) {
            ResponseInputContent file = firstContentPart(sent);
            assertThat(file.asInputFile().fileId()).contains("file_abc");
            assertThat(file.asInputFile().fileData()).isEmpty();
        }
    }

    @Test
    void sendUploadsSeparateFileForDifferentAttachmentContent() {
        capture(textResponse("ok"));
        when(client.files()).thenReturn(fileService);
        when(fileService.create(any(FileCreateParams.class)))
                .thenReturn(fixtureFileObject("file_one"))
                .thenReturn(fixtureFileObject("file_two"));

        OpenAiAdapter adapter = new OpenAiAdapter(client);
        adapter.send("gpt-6-astra", Request.builder().prompt("p")
                .attachments(List.of(Attachment.builder().mediaType("application/pdf").data(new byte[]{1}).build()))
                .build());
        adapter.send("gpt-6-astra", Request.builder().prompt("p")
                .attachments(List.of(Attachment.builder().mediaType("application/pdf").data(new byte[]{2}).build()))
                .build());

        verify(fileService, times(2)).create(any(FileCreateParams.class));
    }

    @Test
    void sendFallsBackToInlineEmbeddingWhenUploadFails() {
        ArgumentCaptor<ResponseCreateParams> captor = capture(textResponse("ok"));
        when(client.files()).thenReturn(fileService);
        when(fileService.create(any(FileCreateParams.class))).thenThrow(new RuntimeException("quota exceeded"));

        Response response = new OpenAiAdapter(client).send("gpt-6-astra", Request.builder().prompt("summarize")
                .attachments(List.of(Attachment.builder().mediaType("application/pdf").data(new byte[]{1, 2, 3}).build()))
                .build());

        assertThat(response.getContent()).isEqualTo("ok"); // upload failure doesn't fail the call
        ResponseInputContent file = firstContentPart(captor.getValue());
        assertThat(file.asInputFile().fileData()).hasValueSatisfying(data -> assertThat(data).startsWith("data:application/pdf;base64,"));
        assertThat(file.asInputFile().fileId()).isEmpty();
    }

    @Test
    void imageAttachmentsAlwaysStayInlineEvenWithFilesApiAvailable() {
        ArgumentCaptor<ResponseCreateParams> captor = capture(textResponse("ok"));

        new OpenAiAdapter(client).send("gpt-6-astra", Request.builder().prompt("describe this")
                .attachments(List.of(Attachment.builder().mediaType("image/png").data(new byte[]{1, 2, 3}).build()))
                .build());

        ResponseInputContent image = firstContentPart(captor.getValue());
        assertThat(image.asInputImage().imageUrl()).hasValueSatisfying(url -> assertThat(url).startsWith("data:image/png;base64,"));
        List<ResponseInputContent> parts = input(captor.getValue()).getLast().asEasyInputMessage().content().asResponseInputMessageContentList();
        assertThat(parts.getLast().asInputText().text()).as("the text follows its attachments").isEqualTo("describe this");
    }

    @Test
    void sendCorrelatesMultipleToolCallsNatively() {
        ArgumentCaptor<ResponseCreateParams> captor = capture(textResponse("ok"));

        ToolCall callA = ToolCall.builder().id("call_a").name("lookup").arguments(Map.of("query", "weather")).build();
        ToolCall callB = ToolCall.builder().id("call_b").name("convert").arguments(Map.of("amount", 5)).build();

        new OpenAiAdapter(client).send("gpt-6-astra", Request.builder()
                .prompt("continue")
                .history(List.of(
                        Message.assistant("", List.of(callA, callB)),
                        Message.tool("call_a", "sunny"),
                        Message.tool("call_b", "5 miles")))
                .build());

        List<ResponseInputItem> sent = input(captor.getValue());
        assertThat(sent).as("two calls, two results, the prompt; no empty assistant text").hasSize(5);

        ResponseFunctionToolCall first = sent.get(0).asFunctionCall();
        assertThat(first.callId()).isEqualTo("call_a");
        assertThat(first.name()).isEqualTo("lookup");
        assertThat(first.arguments()).isEqualTo("{\"query\":\"weather\"}");
        assertThat(sent.get(1).asFunctionCall().callId()).isEqualTo("call_b");

        assertThat(sent.get(2).asFunctionCallOutput().callId()).contains("call_a");
        assertThat(sent.get(2).asFunctionCallOutput().output().asString()).isEqualTo("sunny");
        assertThat(sent.get(3).asFunctionCallOutput().callId()).contains("call_b");
        assertThat(markedToolOutput(sent.get(3)).text()).as("the last result, as one marked block").isEqualTo("5 miles");
        assertThat(sent.get(4).asEasyInputMessage().content().asTextInput()).isEqualTo("continue");
    }

    // ---- prompt caching ----

    @Test
    void theLastToolResultCarriesTheOnlyCacheBreakpointAndThePromptNone() {
        ArgumentCaptor<ResponseCreateParams> captor = capture(textResponse("ok"));
        ToolCall callA = ToolCall.builder().id("call_a").name("lookup").arguments(Map.of()).build();
        ToolCall callB = ToolCall.builder().id("call_b").name("lookup").arguments(Map.of()).build();

        new OpenAiAdapter(client).send("gpt-6-astra", Request.builder()
                .systemInstructions("Be terse.")
                .history(List.of(
                        Message.user("Find the weather."),
                        Message.assistant("", List.of(callA)),
                        Message.tool("call_a", "sunny"),
                        Message.assistant("", List.of(callB)),
                        Message.tool("call_b", "warm")))
                .prompt("Continue.")
                .build());

        List<ResponseInputItem> sent = input(captor.getValue());
        assertThat(sent.get(0).asEasyInputMessage().content().asTextInput()).as("earlier turns stay plain").isEqualTo("Find the weather.");
        assertThat(sent.get(2).asFunctionCallOutput().output().asString()).isEqualTo("sunny");
        ResponseInputTextContent marked = markedToolOutput(sent.get(4));
        assertThat(marked.text()).isEqualTo("warm");
        assertThat(marked._additionalProperties()).containsEntry("prompt_cache_breakpoint", JsonValue.from(Map.of("mode", "explicit")));
        assertThat(sent.get(5).asEasyInputMessage().content().asTextInput()).as("the prompt is never marked").isEqualTo("Continue.");
        assertThat(breakpoints(captor.getValue())).isEqualTo(1);
    }

    @Test
    void aHistoryEndingInAssistantTextIsMarkedAtTheUserTurnBeforeIt() {
        ArgumentCaptor<ResponseCreateParams> captor = capture(textResponse("ok"));

        new OpenAiAdapter(client).send("gpt-6-astra", Request.builder()
                .history(List.of(Message.user("Say hello."), Message.assistant("Hello.")))
                .prompt("Say goodbye.")
                .build());

        List<ResponseInputItem> sent = input(captor.getValue());
        List<ResponseInputContent> parts = sent.get(0).asEasyInputMessage().content().asResponseInputMessageContentList();
        assertThat(parts).singleElement().satisfies(part -> {
            assertThat(part.asInputText().text()).isEqualTo("Say hello.");
            assertThat(part.asInputText()._additionalProperties()).containsKey("prompt_cache_breakpoint");
        });
        assertThat(sent.get(1).asEasyInputMessage().content().asTextInput()).isEqualTo("Hello.");
        assertThat(breakpoints(captor.getValue())).isEqualTo(1);
    }

    @Test
    void aMarkedUserTurnKeepsItsAttachmentsFirstAndMarksItsText() {
        ArgumentCaptor<ResponseCreateParams> captor = capture(textResponse("ok"));

        new OpenAiAdapter(client).send("gpt-6-astra", Request.builder()
                .history(List.of(Message.user("What is this?", List.of(
                        Attachment.builder().mediaType("image/png").data(new byte[]{1}).build()))))
                .prompt("Continue.")
                .build());

        List<ResponseInputContent> parts = input(captor.getValue()).get(0).asEasyInputMessage().content().asResponseInputMessageContentList();
        assertThat(parts).hasSize(2);
        assertThat(parts.get(0).isInputImage()).isTrue();
        assertThat(parts.get(1).asInputText()._additionalProperties()).containsKey("prompt_cache_breakpoint");
    }

    @Test
    void noBreakpointWithoutHistoryOrWithCachingOff() {
        ArgumentCaptor<ResponseCreateParams> captor = capture(textResponse("ok"));
        List<Message> history = List.of(Message.user("Find the weather."), Message.tool("legacy result"));

        OpenAiAdapter adapter = new OpenAiAdapter(client);
        adapter.send("gpt-6-astra", Request.builder().prompt("Hi").build());
        adapter.send("gpt-6-astra", Request.builder().history(history).prompt("Continue.")
                .config(io.github.manishpateluk.llmrouter.config.RouterConfig.builder()
                        .route(List.of(io.github.manishpateluk.llmrouter.config.RouteEntry.of(Provider.OPENAI, "gpt-6-astra")))
                        .promptCaching(false)
                        .build())
                .build());

        assertThat(breakpoints(captor.getAllValues().get(0))).as("OpenAI's implicit breakpoint covers a lone call").isZero();
        assertThat(breakpoints(captor.getAllValues().get(1))).isZero();
        assertThat(input(captor.getAllValues().get(1)).get(1).asEasyInputMessage().content().asTextInput())
                .as("unmarked turns are sent exactly as before").isEqualTo("Tool result: legacy result");
    }

    @Test
    void anAssistantTurnKeepsItsTextBeforeItsToolCalls() {
        ArgumentCaptor<ResponseCreateParams> captor = capture(textResponse("ok"));
        ToolCall call = ToolCall.builder().id("call_a").name("lookup").arguments(Map.of()).build();

        new OpenAiAdapter(client).send("gpt-6-astra", Request.builder()
                .prompt("continue")
                .history(List.of(
                        Message.user("Find the weather."),
                        Message.assistant("Looking it up.", List.of(call)),
                        Message.tool("call_a", "sunny"),
                        Message.assistant("It is sunny.")))
                .build());

        List<ResponseInputItem> sent = input(captor.getValue());
        assertThat(sent).hasSize(6);
        assertThat(sent.get(0).asEasyInputMessage().content().asTextInput()).isEqualTo("Find the weather.");
        assertThat(sent.get(1).asEasyInputMessage().role().toString()).isEqualTo("assistant");
        assertThat(sent.get(1).asEasyInputMessage().content().asTextInput()).isEqualTo("Looking it up.");
        assertThat(sent.get(2).asFunctionCall().arguments()).isEqualTo("{}");
        assertThat(sent.get(3).isFunctionCallOutput()).isTrue();
        assertThat(sent.get(4).asEasyInputMessage().content().asTextInput()).isEqualTo("It is sunny.");
    }

    @Test
    void systemHistoryTurnsJoinTheInstructions() {
        ArgumentCaptor<ResponseCreateParams> captor = capture(textResponse("ok"));

        new OpenAiAdapter(client).send("gpt-6-astra", Request.builder()
                .systemInstructions("Be terse.")
                .history(List.of(Message.system("Earlier: the user likes tables."), Message.user("Hello")))
                .prompt("Again")
                .build());

        assertThat(captor.getValue().instructions()).contains("Be terse.\n\nEarlier: the user likes tables.");
        assertThat(input(captor.getValue())).hasSize(2);
    }

    @Test
    void sendFlattensALegacyToolMessageWithNoCorrelationId() {
        ArgumentCaptor<ResponseCreateParams> captor = capture(textResponse("ok"));

        new OpenAiAdapter(client).send("gpt-6-astra", Request.builder()
                .prompt("continue")
                .history(List.of(Message.tool("legacy result")))
                .build());

        List<ResponseInputContent> parts = input(captor.getValue()).get(0).asEasyInputMessage().content().asResponseInputMessageContentList();
        assertThat(parts).singleElement().satisfies(part -> {
            assertThat(part.asInputText().text()).isEqualTo("Tool result: legacy result");
            assertThat(part.asInputText()._additionalProperties()).as("the last history turn").containsKey("prompt_cache_breakpoint");
        });
    }

    @Test
    void sendsANegotiatedReasoningEffortAndNoneOtherwise() {
        ArgumentCaptor<ResponseCreateParams> captor = capture(textResponse("ok"));

        OpenAiAdapter adapter = new OpenAiAdapter(client);
        adapter.send("gpt-6-astra", Request.builder().prompt("Hi").reasoningEffort("xhigh").build());
        adapter.send("gpt-6-astra", Request.builder().prompt("Hi").build());

        assertThat(captor.getAllValues().get(0).reasoning().flatMap(Reasoning::effort)).contains(ReasoningEffort.XHIGH);
        assertThat(captor.getAllValues().get(1).reasoning()).isEmpty();
    }

    @Test
    void aResponseSchemaIsSentAsANonStrictJsonSchemaFormat() {
        ArgumentCaptor<ResponseCreateParams> captor = capture(textResponse("{}"));

        new OpenAiAdapter(client).send("gpt-6-astra", Request.builder().prompt("Hi")
                .responseSchema(Map.of("type", "object", "properties", Map.of("a", Map.of("type", "string"))))
                .build());

        var format = captor.getValue().text().orElseThrow().format().orElseThrow().asJsonSchema();
        assertThat(format.name()).isEqualTo("response");
        assertThat(format.strict()).contains(false);
        assertThat(format.schema()._additionalProperties()).containsKeys("type", "properties");
    }

    @Test
    void nonPdfDocumentsAreRefusedRatherThanSentAsFiles() {
        OpenAiAdapter adapter = new OpenAiAdapter(client);

        assertThatThrownBy(() -> adapter.send("gpt-6-astra", Request.builder().prompt("read")
                .attachments(List.of(Attachment.builder().mediaType("text/csv").data(new byte[]{1}).build()))
                .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("text/csv");
    }

    @Test
    void reportsCachedAndReasoningTokensFromTheUsageDetails() {
        capture(response(TEXT_OUTPUT.formatted("ok"), """
                {"input_tokens": 5000, "output_tokens": 300, "total_tokens": 5300,
                 "input_tokens_details": {"cached_tokens": 4096, "cache_write_tokens": 512},
                 "output_tokens_details": {"reasoning_tokens": 200}}
                """));

        Response response = new OpenAiAdapter(client).send("gpt-6-astra", Request.builder().prompt("Hi").build());

        assertThat(response.getUsage().getInputTokens()).as("OpenAI's input_tokens already include cached ones").isEqualTo(5000);
        assertThat(response.getUsage().getCacheReadTokens()).isEqualTo(4096);
        assertThat(response.getUsage().getCacheWriteTokens()).as("GPT-6 bills cache writes").isEqualTo(512);
        assertThat(response.getUsage().getReasoningTokens()).isEqualTo(200);
    }

    @Test
    void usageWithoutDetailsReadsAsNoCacheAndNoReasoning() {
        capture(response(TEXT_OUTPUT.formatted("ok"), """
                {"input_tokens": 50, "output_tokens": 3, "total_tokens": 53, "input_tokens_details": {}}
                """));

        Response response = new OpenAiAdapter(client).send("gpt-6-astra", Request.builder().prompt("Hi").build());

        assertThat(response.getUsage().getInputTokens()).isEqualTo(50);
        assertThat(response.getUsage().getCacheReadTokens()).isZero();
        assertThat(response.getUsage().getCacheWriteTokens()).isZero();
        assertThat(response.getUsage().getReasoningTokens()).isZero();
    }

    @Test
    void sendAsyncUsesTheSdksNativeAsyncClient() {
        when(client.async()).thenReturn(asyncClient);
        when(asyncClient.responses()).thenReturn(asyncResponseService);
        when(asyncResponseService.create(any(ResponseCreateParams.class)))
                .thenReturn(CompletableFuture.completedFuture(textResponse("async result")));

        CompletableFuture<Response> future = new OpenAiAdapter(client).sendAsync("gpt-6-astra", Request.builder().prompt("hi").build());

        assertThat(future.join().getContent()).isEqualTo("async result");
    }

    // ---- fixtures ----

    private static final String USAGE = """
            {"input_tokens": 10, "output_tokens": 5, "total_tokens": 15}
            """;

    private static final String TEXT_OUTPUT = """
            [{"type": "message", "id": "m1", "role": "assistant", "status": "completed",
              "content": [{"type": "output_text", "text": "%s", "annotations": []}]}]
            """;

    private ArgumentCaptor<ResponseCreateParams> capture(com.openai.models.responses.Response reply) {
        when(client.responses()).thenReturn(responseService);
        ArgumentCaptor<ResponseCreateParams> captor = ArgumentCaptor.forClass(ResponseCreateParams.class);
        when(responseService.create(captor.capture())).thenReturn(reply);
        return captor;
    }

    private static com.openai.models.responses.Response textResponse(String text) {
        return response(TEXT_OUTPUT.formatted(text), USAGE);
    }

    /** A response as OpenAI's API returns it, read by the SDK's own mapper. */
    private static com.openai.models.responses.Response response(String output, String usage) {
        String json = """
                {"id": "resp_1", "object": "response", "created_at": 0, "model": "gpt-6-astra", "status": "completed",
                 "output": %s, "usage": %s}
                """.formatted(output, usage);
        try {
            return ObjectMappers.jsonMapper().readValue(json, com.openai.models.responses.Response.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<ResponseInputItem> input(ResponseCreateParams sent) {
        return sent.input().orElseThrow().asResponse();
    }

    private static ResponseInputTextContent markedToolOutput(ResponseInputItem item) {
        return item.asFunctionCallOutput().output().asResponseFunctionCallOutputItemList().get(0).asInputText();
    }

    /** How many cache breakpoints the serialized request carries. */
    private static int breakpoints(ResponseCreateParams sent) {
        try {
            String body = ObjectMappers.jsonMapper().writeValueAsString(sent._body());
            return body.split("\"prompt_cache_breakpoint\":\\{\"mode\":\"explicit\"}", -1).length - 1;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ResponseInputContent firstContentPart(ResponseCreateParams sent) {
        return input(sent).getLast().asEasyInputMessage().content().asResponseInputMessageContentList().get(0);
    }

    private static FileObject fixtureFileObject(String id) {
        return FileObject.builder()
                .id(id)
                .bytes(3)
                .createdAt(0)
                .filename("attachment")
                .purpose(FileObject.Purpose.USER_DATA)
                .status(FileObject.Status.PROCESSED)
                .build();
    }
}
