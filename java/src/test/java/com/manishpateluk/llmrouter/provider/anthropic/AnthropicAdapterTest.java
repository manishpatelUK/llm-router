package com.manishpateluk.llmrouter.provider.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.AnthropicClientAsync;
import com.anthropic.models.files.FileMetadata;
import com.anthropic.models.files.FileUploadParams;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DocumentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.Usage;
import com.anthropic.services.async.MessageServiceAsync;
import com.anthropic.services.blocking.FileService;
import com.anthropic.services.blocking.MessageService;
import com.manishpateluk.llmrouter.model.Attachment;
import com.manishpateluk.llmrouter.model.Request;
import com.manishpateluk.llmrouter.model.Response;
import com.manishpateluk.llmrouter.provider.Provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AnthropicAdapterTest {

    @Mock
    private AnthropicClient client;
    @Mock
    private MessageService messageService;
    @Mock
    private FileService fileService;
    @Mock
    private AnthropicClientAsync asyncClient;
    @Mock
    private MessageServiceAsync asyncMessageService;

    @Test
    void idAndAvailability() {
        assertThat(new AnthropicAdapter(client).id()).isEqualTo(Provider.ANTHROPIC);
        assertThat(new AnthropicAdapter(client).isAvailable()).isTrue();
        assertThat(new AnthropicAdapter((String) null).isAvailable()).isFalse();
        assertThat(new AnthropicAdapter("").isAvailable()).isFalse();
    }

    @Test
    void sendMapsRequestAndParsesTextResponse() {
        when(client.messages()).thenReturn(messageService);
        when(messageService.create(any(MessageCreateParams.class))).thenReturn(textMessage("Hello there"));

        AnthropicAdapter adapter = new AnthropicAdapter(client);
        Response response = adapter.send("claude-opus-5", Request.builder().prompt("Hi").build());

        assertThat(response.getContent()).isEqualTo("Hello there");
        assertThat(response.getToolCalls()).isEmpty();
        assertThat(response.getUsage().getInputTokens()).isEqualTo(10);
        assertThat(response.getUsage().getOutputTokens()).isEqualTo(5);
        assertThat(response.getOriginal()).isInstanceOf(Message.class);
    }

    @Test
    void sendSendsSystemInstructionsAndPrompt() {
        when(client.messages()).thenReturn(messageService);
        ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        when(messageService.create(captor.capture())).thenReturn(textMessage("ok"));

        AnthropicAdapter adapter = new AnthropicAdapter(client);
        adapter.send("claude-opus-5", Request.builder()
                .prompt("What's the weather?")
                .systemInstructions("You are terse.")
                .build());

        MessageCreateParams sent = captor.getValue();
        assertThat(sent.model().toString()).isEqualTo("claude-opus-5");
        assertThat(sent.system()).isPresent();
    }

    @Test
    void sendIncludesTemperatureAndTopPWhenPresent() {
        when(client.messages()).thenReturn(messageService);
        ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        when(messageService.create(captor.capture())).thenReturn(textMessage("ok"));

        AnthropicAdapter adapter = new AnthropicAdapter(client);
        adapter.send("claude-opus-5", Request.builder().prompt("hi").temperature(0.7).topP(0.9).build());

        assertThat(captor.getValue().temperature()).isEqualTo(java.util.Optional.of(0.7));
        assertThat(captor.getValue().topP()).isEqualTo(java.util.Optional.of(0.9));
    }

    @Test
    void sendOmitsTemperatureAndTopPWhenAbsent() {
        when(client.messages()).thenReturn(messageService);
        ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        when(messageService.create(captor.capture())).thenReturn(textMessage("ok"));

        AnthropicAdapter adapter = new AnthropicAdapter(client);
        adapter.send("claude-opus-5", Request.builder().prompt("hi").build());

        assertThat(captor.getValue().temperature()).isEmpty();
        assertThat(captor.getValue().topP()).isEmpty();
    }

    @Test
    void sendMapsToolUseBlockToToolCall() {
        when(client.messages()).thenReturn(messageService);
        when(messageService.create(any(MessageCreateParams.class))).thenReturn(toolUseMessage());

        AnthropicAdapter adapter = new AnthropicAdapter(client);
        Response response = adapter.send("claude-opus-5", Request.builder().prompt("lookup something").build());

        assertThat(response.getToolCalls()).hasSize(1);
        assertThat(response.getToolCalls().get(0).getName()).isEqualTo("lookup");
        assertThat(response.getToolCalls().get(0).getArguments()).containsEntry("query", "weather");
    }

    @Test
    void sendUploadsAttachmentOnceAndReusesFileIdOnByteIdenticalRepeat() {
        when(client.messages()).thenReturn(messageService);
        when(client.files()).thenReturn(fileService);
        ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        when(messageService.create(captor.capture())).thenReturn(textMessage("ok"));
        when(fileService.upload(any(FileUploadParams.class))).thenReturn(fixtureFileMetadata("file_abc"));

        AnthropicAdapter adapter = new AnthropicAdapter(client);
        Attachment attachment = Attachment.builder().mediaType("image/png").data(new byte[]{1, 2, 3}).build();
        Request request = Request.builder().prompt("describe this").attachments(List.of(attachment)).build();

        adapter.send("claude-opus-5", request);
        adapter.send("claude-opus-5", request);

        verify(fileService, times(1)).upload(any(FileUploadParams.class));

        for (MessageCreateParams sent : captor.getAllValues()) {
            ImageBlockParam image = firstContentBlock(sent).asImage();
            assertThat(image.source().isFile()).isTrue();
            assertThat(image.source().asFile().fileId()).isEqualTo("file_abc");
            assertThat(image.cacheControl()).isPresent();
        }
    }

    @Test
    void sendUploadsSeparateFileForDifferentAttachmentContent() {
        when(client.messages()).thenReturn(messageService);
        when(client.files()).thenReturn(fileService);
        when(messageService.create(any(MessageCreateParams.class))).thenReturn(textMessage("ok"));
        when(fileService.upload(any(FileUploadParams.class)))
                .thenReturn(fixtureFileMetadata("file_one"))
                .thenReturn(fixtureFileMetadata("file_two"));

        AnthropicAdapter adapter = new AnthropicAdapter(client);
        adapter.send("claude-opus-5", Request.builder().prompt("p")
                .attachments(List.of(Attachment.builder().mediaType("image/png").data(new byte[]{1}).build()))
                .build());
        adapter.send("claude-opus-5", Request.builder().prompt("p")
                .attachments(List.of(Attachment.builder().mediaType("image/png").data(new byte[]{2}).build()))
                .build());

        verify(fileService, times(2)).upload(any(FileUploadParams.class));
    }

    @Test
    void sendUploadsDocumentAttachmentAndReferencesFileId() {
        when(client.messages()).thenReturn(messageService);
        when(client.files()).thenReturn(fileService);
        ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        when(messageService.create(captor.capture())).thenReturn(textMessage("ok"));
        when(fileService.upload(any(FileUploadParams.class))).thenReturn(fixtureFileMetadata("file_doc"));

        AnthropicAdapter adapter = new AnthropicAdapter(client);
        adapter.send("claude-opus-5", Request.builder().prompt("summarize")
                .attachments(List.of(Attachment.builder().mediaType("application/pdf").data(new byte[]{1, 2, 3}).build()))
                .build());

        DocumentBlockParam document = firstContentBlock(captor.getValue()).asDocument();
        assertThat(document.source().isFile()).isTrue();
        assertThat(document.source().asFile().fileId()).isEqualTo("file_doc");
        assertThat(document.cacheControl()).isPresent();
    }

    @Test
    void sendFallsBackToInlineEmbeddingWhenUploadFails() {
        when(client.messages()).thenReturn(messageService);
        when(client.files()).thenReturn(fileService);
        ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        when(messageService.create(captor.capture())).thenReturn(textMessage("ok"));
        when(fileService.upload(any(FileUploadParams.class))).thenThrow(new RuntimeException("quota exceeded"));

        AnthropicAdapter adapter = new AnthropicAdapter(client);
        Response response = adapter.send("claude-opus-5", Request.builder().prompt("describe this")
                .attachments(List.of(Attachment.builder().mediaType("image/png").data(new byte[]{1, 2, 3}).build()))
                .build());

        assertThat(response.getContent()).isEqualTo("ok"); // upload failure doesn't fail the call
        ImageBlockParam image = firstContentBlock(captor.getValue()).asImage();
        assertThat(image.source().isBase64()).isTrue();
        assertThat(image.cacheControl()).isPresent(); // cache_control still set on the inline fallback
    }

    private static ContentBlockParam firstContentBlock(MessageCreateParams sent) {
        MessageParam userMessage = sent.messages().get(sent.messages().size() - 1);
        return userMessage.content().asBlockParams().get(0);
    }

    private static FileMetadata fixtureFileMetadata(String id) {
        return FileMetadata.builder()
                .id(id)
                .createdAt(OffsetDateTime.now())
                .filename("attachment")
                .mimeType("application/octet-stream")
                .sizeBytes(3)
                .build();
    }

    @Test
    void sendAsyncUsesTheSdksNativeAsyncClient() {
        when(client.async()).thenReturn(asyncClient);
        when(asyncClient.messages()).thenReturn(asyncMessageService);
        when(asyncMessageService.create(any(MessageCreateParams.class)))
                .thenReturn(CompletableFuture.completedFuture(textMessage("async result")));

        AnthropicAdapter adapter = new AnthropicAdapter(client);
        CompletableFuture<Response> future = adapter.sendAsync("claude-opus-5", Request.builder().prompt("hi").build());

        assertThat(future.join().getContent()).isEqualTo("async result");
    }

    private static Usage fixtureUsage(long inputTokens, long outputTokens) {
        return Usage.builder()
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
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
                .build();
    }

    private static Message.Builder fixtureMessageBuilder(String id) {
        return Message.builder()
                .id(id)
                .model("claude-opus-5")
                .container(java.util.Optional.empty())
                .stopDetails(java.util.Optional.empty())
                .stopReason(java.util.Optional.empty())
                .stopSequence(java.util.Optional.empty());
    }

    private static Message textMessage(String text) {
        return fixtureMessageBuilder("msg_1")
                .content(List.of(ContentBlock.ofText(
                        TextBlock.builder().text(text).citations(List.of()).build())))
                .usage(fixtureUsage(10, 5))
                .build();
    }

    private static Message toolUseMessage() {
        ToolUseBlock toolUse = ToolUseBlock.builder()
                .id("call_1")
                .name("lookup")
                .input(com.anthropic.core.JsonValue.from(Map.of("query", "weather")))
                .caller(com.anthropic.models.messages.ToolUseBlock.Caller.ofDirect(
                        com.anthropic.models.messages.DirectCaller.builder().build()))
                .build();
        return fixtureMessageBuilder("msg_2")
                .content(List.of(ContentBlock.ofToolUse(toolUse)))
                .usage(fixtureUsage(8, 4))
                .build();
    }
}
