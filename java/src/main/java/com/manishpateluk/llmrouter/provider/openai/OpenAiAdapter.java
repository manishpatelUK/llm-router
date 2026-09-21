package com.manishpateluk.llmrouter.provider.openai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.manishpateluk.llmrouter.capability.ModelCapabilityTable;
import com.manishpateluk.llmrouter.capability.ModelEntry;
import com.manishpateluk.llmrouter.model.Attachment;
import com.manishpateluk.llmrouter.model.Request;
import com.manishpateluk.llmrouter.model.Response;
import com.manishpateluk.llmrouter.model.Role;
import com.manishpateluk.llmrouter.model.ToolCall;
import com.manishpateluk.llmrouter.model.ToolDefinition;
import com.manishpateluk.llmrouter.provider.Provider;
import com.manishpateluk.llmrouter.provider.ProviderAdapter;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.ResponseFormatJsonSchema;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionContentPartImage;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.completions.CompletionUsage;
import com.openai.models.files.FileCreateParams;
import com.openai.models.files.FilePurpose;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ProviderAdapter} for OpenAI, built on the official {@code openai-java} SDK.
 * {@link #sendAsync} uses the SDK's own {@code client.async()} view, which is genuinely
 * non-blocking — not the interface's thread-pool fallback.
 *
 * <p>Known scope limits (documented rather than silently guessed at): a history turn is sent
 * using native {@code tool_calls}/a {@code tool}-role message only when the caller populated
 * {@code Message.toolCalls}/{@code toolCallId} (§3); without those, a {@code TOOL}-role history
 * entry falls back to a plain user-role message instead. {@code Response.generatedFiles} is left
 * empty, since nothing in a plain chat-completions request causes the model to produce a file.
 *
 * <p>Repeated-attachment optimization — documents only, not images: a non-image attachment is
 * opportunistically uploaded once via OpenAI's Files API ({@code purpose=user_data}) and
 * referenced by file id on every subsequent call whose attachment has byte-identical content,
 * instead of re-embedding and re-transmitting the same base64 payload on every turn. The Chat
 * Completions API has no equivalent for images — {@code image_url} only ever accepts a URL or an
 * inline data URI, never a file id — so image attachments always re-embed inline exactly as
 * before; this is a real capability gap in the API, not an oversight here. The upload cache is
 * keyed by a content hash ({@code mediaType} + bytes) and scoped to this adapter instance, never
 * shared across providers or instances. It's a pure optimization, never a correctness dependency:
 * an upload failure (network blip, permissions, size limits) falls back to full inline embedding
 * for that one attempt without being cached, so the next call retries the upload rather than
 * giving up on it permanently. The cache has no eviction or TTL, so a very long-lived adapter
 * instance juggling a very large number of distinct document attachments will grow it
 * unboundedly — fine for the common case, worth knowing for a long-lived, high-cardinality server.
 */
public final class OpenAiAdapter implements ProviderAdapter {

    private static final Logger log = LoggerFactory.getLogger(OpenAiAdapter.class);
    private static final long DEFAULT_MAX_TOKENS = 4096L;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final OpenAIClient client;
    private final Map<String, String> uploadedFileIdsByContentHash = new ConcurrentHashMap<>();

    /** Builds its own client from {@code apiKey}; {@link #isAvailable()} is {@code false} if it's null/blank. */
    public OpenAiAdapter(String apiKey) {
        this(apiKey == null || apiKey.isBlank() ? null : OpenAIOkHttpClient.builder().apiKey(apiKey).build());
    }

    /** Accepts a pre-configured client directly — the seam tests mock. */
    public OpenAiAdapter(OpenAIClient client) {
        this.client = client;
    }

    @Override
    public Provider id() {
        return Provider.OPENAI;
    }

    @Override
    public boolean isAvailable() {
        return client != null;
    }

    @Override
    public Response send(String model, Request adaptedRequest) {
        requireAvailable();
        ChatCompletion completion = client.chat().completions().create(toParams(model, adaptedRequest));
        return fromCompletion(completion);
    }

    @Override
    public CompletableFuture<Response> sendAsync(String model, Request adaptedRequest) {
        requireAvailable();
        return client.async().chat().completions().create(toParams(model, adaptedRequest)).thenApply(this::fromCompletion);
    }

    private void requireAvailable() {
        if (client == null) {
            throw new IllegalStateException("OpenAiAdapter has no credentials configured");
        }
    }

    private ChatCompletionCreateParams toParams(String model, Request request) {
        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                .model(model)
                .maxTokens(resolveMaxTokens(model));

        if (request.getTemperature() != null) {
            builder.temperature(request.getTemperature());
        }
        if (request.getTopP() != null) {
            builder.topP(request.getTopP());
        }

        String system = buildSystemInstructions(request);
        if (system != null) {
            builder.addSystemMessage(system);
        }

        for (var message : request.getHistory()) {
            switch (message.getRole()) {
                case USER -> builder.addUserMessage(message.getContent());
                case ASSISTANT -> addAssistantMessage(builder, message);
                case SYSTEM -> { /* folded into system instructions above */ }
                case TOOL -> addToolResultMessage(builder, message);
            }
        }

        if (request.getAttachments().isEmpty()) {
            builder.addUserMessage(request.getPrompt());
        } else {
            List<ChatCompletionContentPart> parts = new ArrayList<>();
            for (Attachment attachment : request.getAttachments()) {
                parts.add(toContentPart(attachment));
            }
            parts.add(ChatCompletionContentPart.ofText(
                    com.openai.models.chat.completions.ChatCompletionContentPartText.builder()
                            .text(request.getPrompt())
                            .build()));
            builder.addUserMessageOfArrayOfContentParts(parts);
        }

        for (ToolDefinition tool : request.getTools()) {
            builder.addTool(toOpenAiTool(tool));
        }

        if (request.getResponseSchema() != null) {
            builder.responseFormat(ResponseFormatJsonSchema.builder()
                    .jsonSchema(ResponseFormatJsonSchema.JsonSchema.builder()
                            .name("response")
                            .schema(toJsonSchema(request.getResponseSchema()))
                            .build())
                    .build());
        }

        return builder.build();
    }

    /**
     * Sends {@code message} as a native assistant turn carrying one OpenAI function tool call
     * per requested call. Falls back to a plain assistant text message when {@code toolCalls} is
     * empty (the common, no-tool-call case).
     */
    private static void addAssistantMessage(ChatCompletionCreateParams.Builder builder, com.manishpateluk.llmrouter.model.Message message) {
        if (message.getToolCalls().isEmpty()) {
            builder.addAssistantMessage(message.getContent());
            return;
        }

        ChatCompletionAssistantMessageParam.Builder assistant =
                ChatCompletionAssistantMessageParam.builder().content(message.getContent());
        for (ToolCall call : message.getToolCalls()) {
            assistant.addToolCall(ChatCompletionMessageFunctionToolCall.builder()
                    .id(call.getId())
                    .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                            .name(call.getName())
                            .arguments(toArgumentsJson(call.getArguments()))
                            .build())
                    .build());
        }
        builder.addMessage(assistant.build());
    }

    /**
     * Sends {@code message} as a native {@code tool}-role message when it carries a {@code
     * toolCallId}; otherwise falls back to a plain flattened user message, exactly as before this
     * correlation support existed.
     */
    private static void addToolResultMessage(ChatCompletionCreateParams.Builder builder, com.manishpateluk.llmrouter.model.Message message) {
        if (message.getToolCallId() == null) {
            builder.addUserMessage("Tool result: " + message.getContent());
            return;
        }
        builder.addMessage(ChatCompletionToolMessageParam.builder()
                .toolCallId(message.getToolCallId())
                .content(message.getContent())
                .build());
    }

    private static String toArgumentsJson(Map<String, Object> arguments) {
        try {
            return JSON.writeValueAsString(arguments == null ? Map.of() : arguments);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize tool call arguments", e);
        }
    }

    private static String buildSystemInstructions(Request request) {
        StringBuilder combined = new StringBuilder();
        if (request.getSystemInstructions() != null && !request.getSystemInstructions().isBlank()) {
            combined.append(request.getSystemInstructions());
        }
        for (var message : request.getHistory()) {
            if (message.getRole() == Role.SYSTEM) {
                if (combined.length() > 0) {
                    combined.append("\n\n");
                }
                combined.append(message.getContent());
            }
        }
        return combined.length() == 0 ? null : combined.toString();
    }

    private static long resolveMaxTokens(String model) {
        return ModelCapabilityTable.findModel(Provider.OPENAI, model)
                .map(ModelEntry::getMaxOutputTokens)
                .map(Integer::longValue)
                .orElse(DEFAULT_MAX_TOKENS);
    }

    private static ChatCompletionFunctionTool toOpenAiTool(ToolDefinition tool) {
        return ChatCompletionFunctionTool.builder()
                .function(FunctionDefinition.builder()
                        .name(tool.getName())
                        .description(tool.getDescription())
                        .parameters(toFunctionParameters(tool.getParameters()))
                        .build())
                .build();
    }

    private static FunctionParameters toFunctionParameters(Map<String, Object> parameters) {
        FunctionParameters.Builder builder = FunctionParameters.builder();
        if (parameters != null) {
            for (Map.Entry<String, Object> entry : parameters.entrySet()) {
                builder.putAdditionalProperty(entry.getKey(), JsonValue.from(entry.getValue()));
            }
        }
        return builder.build();
    }

    private static ResponseFormatJsonSchema.JsonSchema.Schema toJsonSchema(Map<String, Object> responseSchema) {
        ResponseFormatJsonSchema.JsonSchema.Schema.Builder builder = ResponseFormatJsonSchema.JsonSchema.Schema.builder();
        for (Map.Entry<String, Object> entry : responseSchema.entrySet()) {
            builder.putAdditionalProperty(entry.getKey(), JsonValue.from(entry.getValue()));
        }
        return builder.build();
    }

    private ChatCompletionContentPart toContentPart(Attachment attachment) {
        String mediaType = attachment.getMediaType();
        if (mediaType != null && mediaType.startsWith("image/")) {
            // No file-reference path exists for images in Chat Completions — always inline. See class javadoc.
            String base64Data = Base64.getEncoder().encodeToString(attachment.getData());
            return ChatCompletionContentPart.ofImageUrl(ChatCompletionContentPartImage.builder()
                    .imageUrl(ChatCompletionContentPartImage.ImageUrl.builder()
                            .url("data:" + mediaType + ";base64," + base64Data)
                            .build())
                    .build());
        }

        String filename = attachment.getFilename() == null ? "attachment" : attachment.getFilename();
        String fileId = uploadOrReuseFileId(attachment);
        if (fileId != null) {
            return ChatCompletionContentPart.ofFile(ChatCompletionContentPart.File.builder()
                    .file(ChatCompletionContentPart.File.FileObject.builder()
                            .fileId(fileId)
                            .filename(filename)
                            .build())
                    .build());
        }

        // Files API upload unavailable or failed for this attempt — fall back to full inline embedding.
        String base64Data = Base64.getEncoder().encodeToString(attachment.getData());
        return ChatCompletionContentPart.ofFile(ChatCompletionContentPart.File.builder()
                .file(ChatCompletionContentPart.File.FileObject.builder()
                        .fileData("data:" + mediaType + ";base64," + base64Data)
                        .filename(filename)
                        .build())
                .build());
    }

    /**
     * Uploads {@code attachment} via the Files API on first sight of its exact content, caching
     * the resulting file id by content hash so byte-identical attachments on later calls skip the
     * upload and the base64 re-embedding entirely — see the class-level javadoc. Returns
     * {@code null} (never throws) if the upload itself fails, so the caller can fall back to
     * inline embedding for this one attempt instead of failing the whole request over what is
     * purely an optimization.
     */
    private String uploadOrReuseFileId(Attachment attachment) {
        String contentHash = contentHash(attachment);
        return uploadedFileIdsByContentHash.computeIfAbsent(contentHash, key -> {
            try {
                return client.files()
                        .create(FileCreateParams.builder().file(attachment.getData()).purpose(FilePurpose.USER_DATA).build())
                        .id();
            } catch (RuntimeException e) {
                log.warn("Attachment upload to OpenAI Files API failed; falling back to inline embedding "
                        + "for this attempt: {}", e.getMessage());
                return null;
            }
        });
    }

    private static String contentHash(Attachment attachment) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(String.valueOf(attachment.getMediaType()).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(attachment.getData());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private Response fromCompletion(ChatCompletion completion) {
        ChatCompletionMessage message = completion.choices().get(0).message();

        List<ToolCall> toolCalls = new ArrayList<>();
        message.toolCalls().ifPresent(calls -> {
            for (ChatCompletionMessageToolCall call : calls) {
                if (call.isFunction()) {
                    toolCalls.add(toToolCall(call.asFunction()));
                }
            }
        });

        int inputTokens = 0;
        int outputTokens = 0;
        if (completion.usage().isPresent()) {
            CompletionUsage usage = completion.usage().get();
            inputTokens = (int) usage.promptTokens();
            outputTokens = (int) usage.completionTokens();
        }

        return Response.builder()
                .content(message.content().orElse(""))
                .toolCalls(toolCalls)
                .usage(com.manishpateluk.llmrouter.model.Usage.builder()
                        .inputTokens(inputTokens)
                        .outputTokens(outputTokens)
                        .reasoningTokens(0)
                        .estimatedCostUsdCents(0) // finalized by the router core against the capability table
                        .build())
                .original(completion)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static ToolCall toToolCall(ChatCompletionMessageFunctionToolCall call) {
        Map<String, Object> arguments = (Map<String, Object>) call.function().arguments(Map.class);
        return ToolCall.builder()
                .id(call.id())
                .name(call.function().name())
                .arguments(arguments)
                .build();
    }
}
