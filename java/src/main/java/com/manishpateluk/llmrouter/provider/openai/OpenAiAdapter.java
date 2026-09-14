package com.manishpateluk.llmrouter.provider.openai;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

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
import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionContentPartImage;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.completions.CompletionUsage;

/**
 * {@link ProviderAdapter} for OpenAI, built on the official {@code openai-java} SDK.
 * {@link #sendAsync} uses the SDK's own {@code client.async()} view, which is genuinely
 * non-blocking — not the interface's thread-pool fallback.
 *
 * <p>Known scope limits (documented rather than silently guessed at): the unified
 * {@code Message} history shape (§3) carries no tool-call id, so a {@code TOOL}-role history
 * entry is sent as a plain user-role message rather than a native tool-result message;
 * {@code Response.generatedFiles} is left empty, since nothing in a plain chat-completions
 * request causes the model to produce a file.
 */
public final class OpenAiAdapter implements ProviderAdapter {

    private static final long DEFAULT_MAX_TOKENS = 4096L;

    private final OpenAIClient client;

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

        String system = buildSystemInstructions(request);
        if (system != null) {
            builder.addSystemMessage(system);
        }

        for (var message : request.getHistory()) {
            switch (message.getRole()) {
                case USER -> builder.addUserMessage(message.getContent());
                case ASSISTANT -> builder.addAssistantMessage(message.getContent());
                case SYSTEM -> { /* folded into system instructions above */ }
                case TOOL -> builder.addUserMessage("Tool result: " + message.getContent());
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

    private static ChatCompletionContentPart toContentPart(Attachment attachment) {
        String base64Data = Base64.getEncoder().encodeToString(attachment.getData());
        String mediaType = attachment.getMediaType();
        if (mediaType != null && mediaType.startsWith("image/")) {
            return ChatCompletionContentPart.ofImageUrl(ChatCompletionContentPartImage.builder()
                    .imageUrl(ChatCompletionContentPartImage.ImageUrl.builder()
                            .url("data:" + mediaType + ";base64," + base64Data)
                            .build())
                    .build());
        }
        return ChatCompletionContentPart.ofFile(ChatCompletionContentPart.File.builder()
                .file(ChatCompletionContentPart.File.FileObject.builder()
                        .fileData("data:" + mediaType + ";base64," + base64Data)
                        .filename(attachment.getFilename() == null ? "attachment" : attachment.getFilename())
                        .build())
                .build());
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
