package com.manishpateluk.llmrouter.provider.anthropic;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.Base64PdfSource;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DocumentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolUseBlock;
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

/**
 * {@link ProviderAdapter} for Anthropic (Claude), built on the official {@code anthropic-java}
 * SDK. {@link #sendAsync} uses the SDK's own {@code client.async()} view, which is genuinely
 * non-blocking — not the interface's thread-pool fallback.
 *
 * <p>Known scope limits (documented rather than silently guessed at): the unified
 * {@code Message} history shape (§3) carries no tool-call id, so a {@code TOOL}-role history
 * entry is sent as a plain user-role message rather than a native {@code tool_result} block;
 * non-image attachments are sent as PDF documents (the well-documented case) — other document
 * media types are not yet mapped; {@code Response.generatedFiles} is left empty, since nothing
 * in a plain request causes Claude to produce a file without the caller also requesting a
 * code-execution-capable tool, which this adapter doesn't add implicitly.
 */
public final class AnthropicAdapter implements ProviderAdapter {

    private static final long DEFAULT_MAX_TOKENS = 4096L;

    private final AnthropicClient client;

    /** Builds its own client from {@code apiKey}; {@link #isAvailable()} is {@code false} if it's null/blank. */
    public AnthropicAdapter(String apiKey) {
        this(apiKey == null || apiKey.isBlank() ? null : AnthropicOkHttpClient.builder().apiKey(apiKey).build());
    }

    /** Accepts a pre-configured client directly — the seam tests mock. */
    public AnthropicAdapter(AnthropicClient client) {
        this.client = client;
    }

    @Override
    public Provider id() {
        return Provider.ANTHROPIC;
    }

    @Override
    public boolean isAvailable() {
        return client != null;
    }

    @Override
    public Response send(String model, Request adaptedRequest) {
        requireAvailable();
        Message message = client.messages().create(toParams(model, adaptedRequest));
        return fromMessage(message);
    }

    @Override
    public CompletableFuture<Response> sendAsync(String model, Request adaptedRequest) {
        requireAvailable();
        return client.async().messages().create(toParams(model, adaptedRequest)).thenApply(this::fromMessage);
    }

    private void requireAvailable() {
        if (client == null) {
            throw new IllegalStateException("AnthropicAdapter has no credentials configured");
        }
    }

    private MessageCreateParams toParams(String model, Request request) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(resolveMaxTokens(model));

        String system = buildSystemInstructions(request);
        if (system != null) {
            builder.system(system);
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
            List<ContentBlockParam> blocks = new ArrayList<>();
            for (Attachment attachment : request.getAttachments()) {
                blocks.add(toContentBlockParam(attachment));
            }
            blocks.add(ContentBlockParam.ofText(request.getPrompt()));
            builder.addUserMessageOfBlockParams(blocks);
        }

        for (ToolDefinition tool : request.getTools()) {
            builder.addTool(toAnthropicTool(tool));
        }

        if (request.getResponseSchema() != null) {
            builder.outputConfig(OutputConfig.builder()
                    .format(JsonOutputFormat.builder().schema(toOutputSchema(request.getResponseSchema())).build())
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
        return ModelCapabilityTable.findModel(Provider.ANTHROPIC, model)
                .map(ModelEntry::getMaxOutputTokens)
                .map(Integer::longValue)
                .orElse(DEFAULT_MAX_TOKENS);
    }

    private static Tool toAnthropicTool(ToolDefinition tool) {
        return Tool.builder()
                .name(tool.getName())
                .description(tool.getDescription())
                .inputSchema(toInputSchema(tool.getParameters()))
                .build();
    }

    @SuppressWarnings("unchecked")
    private static Tool.InputSchema toInputSchema(Map<String, Object> parameters) {
        Tool.InputSchema.Builder schema = Tool.InputSchema.builder();
        if (parameters != null) {
            if (parameters.get("properties") instanceof Map<?, ?> properties) {
                Tool.InputSchema.Properties.Builder propsBuilder = Tool.InputSchema.Properties.builder();
                for (Map.Entry<?, ?> entry : properties.entrySet()) {
                    propsBuilder.putAdditionalProperty(String.valueOf(entry.getKey()), JsonValue.from(entry.getValue()));
                }
                schema.properties(propsBuilder.build());
            }
            if (parameters.get("required") instanceof List<?> required) {
                schema.required(required.stream().map(String::valueOf).collect(Collectors.toList()));
            }
        }
        return schema.build();
    }

    private static JsonOutputFormat.Schema toOutputSchema(Map<String, Object> responseSchema) {
        JsonOutputFormat.Schema.Builder schema = JsonOutputFormat.Schema.builder();
        for (Map.Entry<String, Object> entry : responseSchema.entrySet()) {
            schema.putAdditionalProperty(entry.getKey(), JsonValue.from(entry.getValue()));
        }
        return schema.build();
    }

    private static ContentBlockParam toContentBlockParam(Attachment attachment) {
        String base64Data = Base64.getEncoder().encodeToString(attachment.getData());
        String mediaType = attachment.getMediaType();
        if (mediaType != null && mediaType.startsWith("image/")) {
            return ContentBlockParam.ofImage(ImageBlockParam.builder()
                    .source(Base64ImageSource.builder()
                            .mediaType(Base64ImageSource.MediaType.of(mediaType))
                            .data(base64Data)
                            .build())
                    .build());
        }
        return ContentBlockParam.ofDocument(DocumentBlockParam.builder()
                .source(Base64PdfSource.builder().data(base64Data).build())
                .build());
    }

    private Response fromMessage(Message message) {
        StringBuilder content = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            if (block.isText()) {
                content.append(block.asText().text());
            } else if (block.isToolUse()) {
                toolCalls.add(toToolCall(block.asToolUse()));
            }
        }

        return Response.builder()
                .content(content.toString())
                .toolCalls(toolCalls)
                .usage(com.manishpateluk.llmrouter.model.Usage.builder()
                        .inputTokens((int) message.usage().inputTokens())
                        .outputTokens((int) message.usage().outputTokens())
                        .reasoningTokens(0)
                        .estimatedCostUsdCents(0) // finalized by the router core against the capability table
                        .build())
                .original(message)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static ToolCall toToolCall(ToolUseBlock toolUse) {
        Map<String, Object> arguments = (Map<String, Object>) toolUse._input().convert(Map.class);
        return ToolCall.builder()
                .id(toolUse.id())
                .name(toolUse.name())
                .arguments(arguments)
                .build();
    }
}
