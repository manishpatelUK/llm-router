package io.github.manishpateluk.llmrouter.provider.openai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import tools.jackson.databind.ObjectMapper;
import io.github.manishpateluk.llmrouter.capability.ModelCapabilityTable;
import io.github.manishpateluk.llmrouter.capability.ModelEntry;
import io.github.manishpateluk.llmrouter.model.Attachment;
import io.github.manishpateluk.llmrouter.model.EmbeddingResponse;
import io.github.manishpateluk.llmrouter.model.Message;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.model.Role;
import io.github.manishpateluk.llmrouter.model.ToolCall;
import io.github.manishpateluk.llmrouter.model.ToolDefinition;
import io.github.manishpateluk.llmrouter.provider.Provider;
import io.github.manishpateluk.llmrouter.provider.ProviderAdapter;
import io.github.manishpateluk.llmrouter.provider.TransientFailures;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.OpenAIIoException;
import com.openai.helpers.ResponseAccumulator;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import com.openai.models.responses.ResponseFunctionCallOutputItem;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputContent;
import com.openai.models.responses.ResponseInputFile;
import com.openai.models.responses.ResponseInputImage;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseInputText;
import com.openai.models.responses.ResponseInputTextContent;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseTextConfig;
import com.openai.models.responses.ResponseUsage;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.Embedding;
import com.openai.models.embeddings.EmbeddingCreateParams;
import com.openai.models.files.FileCreateParams;
import com.openai.models.files.FilePurpose;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ProviderAdapter} for OpenAI, built on the official {@code openai-java} SDK and OpenAI's
 * Responses API ({@code /v1/responses}). Chat Completions refuses function tools on GPT-6 models
 * whenever reasoning is on, so an agent step there could only run with reasoning off; the
 * Responses API takes both. {@link #sendAsync} uses the SDK's own {@code client.async()} view,
 * which is genuinely non-blocking — not the interface's thread-pool fallback.
 *
 * <p>Every request is stateless: it carries the whole conversation, sets {@code store: false} so
 * OpenAI keeps no copy of the response (as with Chat Completions), and never uses
 * {@code previous_response_id}. The model's reasoning items aren't sent back on later steps, since
 * {@code Message} has nowhere to keep them; the model reasons afresh on each step, as it did over
 * Chat Completions.
 *
 * <p>Prompt caching (§4.3): unless {@code RouterConfig.promptCaching} is off, the last block of the
 * last user message or tool result in the history carries an explicit
 * {@code prompt_cache_breakpoint}, so the next call of a tool loop — which repeats that history and
 * changes only the prompt — reads the whole conversation from the cache. OpenAI's own implicit
 * breakpoint, at the end of each request, still covers a call with no history. An assistant text
 * turn can't carry a breakpoint (its content isn't an input block), so a history ending in one is
 * marked at the turn before it.
 *
 * <p>Known scope limits (documented rather than silently guessed at): a history turn is sent as
 * native {@code function_call}/{@code function_call_output} items only when the caller populated
 * {@code Message.toolCalls}/{@code toolCallId} (§3); without those, a {@code TOOL}-role history
 * entry falls back to a plain user-role message instead. {@code Response.generatedFiles} is left
 * empty, since no built-in tool that produces files is ever requested.
 *
 * <p>Repeated-attachment optimization — documents only, not images: a non-image attachment is
 * opportunistically uploaded once via OpenAI's Files API ({@code purpose=user_data}) and
 * referenced by file id on every subsequent call whose attachment has byte-identical content,
 * instead of re-embedding and re-transmitting the same base64 payload on every turn. Image
 * attachments are always sent inline as a data URI. The upload cache is
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
    private static final JsonValue CACHE_BREAKPOINT = JsonValue.from(Map.of("mode", "explicit"));

    private final OpenAIClient client;
    private final Map<String, String> uploadedFileIdsByContentHash = new ConcurrentHashMap<>();

    /**
     * Builds its own client from {@code apiKey}; {@link #isAvailable()} is {@code false} if it's
     * null/blank. The client makes no retries of its own: retrying is the router's job, under
     * {@code RouterConfig.maxRetries} (§5.5).
     */
    public OpenAiAdapter(String apiKey) {
        this(newClient(apiKey, null));
    }

    /** The client {@link #OpenAiAdapter(String)} builds, or {@code null} without a key; {@code baseUrl} {@code null} for OpenAI's own. */
    static OpenAIClient newClient(String apiKey, String baseUrl) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder().apiKey(apiKey).maxRetries(0);
        if (baseUrl != null) {
            builder.baseUrl(baseUrl);
        }
        return builder.build();
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

    /** Also recognises the SDK's own HTTP and I/O exceptions; see {@link ProviderAdapter#isTransient}. */
    @Override
    public boolean isTransient(Throwable failure) {
        OpenAIServiceException service = TransientFailures.find(failure, OpenAIServiceException.class);
        if (service != null) {
            return TransientFailures.isTransientStatus(service.statusCode());
        }
        return TransientFailures.find(failure, OpenAIIoException.class) != null || ProviderAdapter.super.isTransient(failure);
    }

    @Override
    public Duration retryAfter(Throwable failure) {
        OpenAIServiceException service = TransientFailures.find(failure, OpenAIServiceException.class);
        return service == null ? null : TransientFailures.retryAfter(name -> service.headers().values(name));
    }

    @Override
    public Response send(String model, Request adaptedRequest) {
        requireAvailable();
        return fromResponse(client.responses().create(toParams(model, adaptedRequest)));
    }

    @Override
    public CompletableFuture<Response> sendAsync(String model, Request adaptedRequest) {
        requireAvailable();
        return client.async().responses().create(toParams(model, adaptedRequest)).thenApply(this::fromResponse);
    }

    /** OpenAI's small, inexpensive embedding model (1536 dimensions by default). */
    public static final String DEFAULT_EMBEDDING_MODEL = "text-embedding-3-small";

    @Override
    public String defaultEmbeddingModel() {
        return DEFAULT_EMBEDDING_MODEL;
    }

    @Override
    public EmbeddingResponse embed(String model, List<String> texts, Integer dimensions) {
        requireAvailable();
        EmbeddingCreateParams.Builder params = EmbeddingCreateParams.builder().model(model).inputOfArrayOfStrings(texts);
        if (dimensions != null) {
            params.dimensions(dimensions);
        }
        CreateEmbeddingResponse response = client.embeddings().create(params.build());
        float[][] vectors = new float[texts.size()][];
        for (Embedding embedding : response.data()) {
            List<Float> values = embedding.embedding();
            float[] vector = new float[values.size()];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = values.get(i);
            }
            vectors[(int) embedding.index()] = vector;
        }
        return EmbeddingResponse.builder()
                .vectors(List.of(vectors))
                .usage(io.github.manishpateluk.llmrouter.model.Usage.builder()
                        .inputTokens((int) response.usage().promptTokens())
                        .outputTokens(0)
                        .build())
                .build();
    }

    /**
     * Streams response events, passing on each text delta and accumulating them into the same
     * response {@link #send} would return; the final {@code response.completed} event carries the
     * usage, so token counts (and so cost) are still reported.
     */
    @Override
    public Response sendStreaming(String model, Request adaptedRequest, Consumer<String> onText) {
        requireAvailable();
        ResponseAccumulator accumulator = ResponseAccumulator.create();
        try (StreamResponse<ResponseStreamEvent> stream = client.responses().createStreaming(toParams(model, adaptedRequest))) {
            stream.stream().forEach(event -> {
                accumulator.accumulate(event);
                if (event.isOutputTextDelta()) {
                    String text = event.asOutputTextDelta().delta();
                    if (!text.isEmpty()) {
                        onText.accept(text);
                    }
                }
            });
        }
        return fromResponse(accumulator.response());
    }

    private void requireAvailable() {
        if (client == null) {
            throw new IllegalStateException("OpenAiAdapter has no credentials configured");
        }
    }

    private ResponseCreateParams toParams(String model, Request request) {
        ResponseCreateParams.Builder builder = ResponseCreateParams.builder()
                .model(model)
                .maxOutputTokens(resolveMaxTokens(model, request))
                .store(false);

        if (request.getTemperature() != null) {
            builder.temperature(request.getTemperature());
        }
        if (request.getTopP() != null) {
            builder.topP(request.getTopP());
        }
        if (request.getReasoningEffort() != null) {
            builder.reasoning(Reasoning.builder().effort(ReasoningEffort.of(request.getReasoningEffort())).build());
        }

        String system = buildSystemInstructions(request);
        if (system != null) {
            builder.instructions(system);
        }

        boolean caching = request.getConfig() == null || request.getConfig().isPromptCaching();
        List<Message> history = request.getHistory();
        int breakpoint = caching ? cacheBreakpointIndex(history) : -1;
        List<ResponseInputItem> input = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            Message message = history.get(i);
            switch (message.getRole()) {
                case USER -> input.add(userMessage(message.getContent(), message.getAttachments(), i == breakpoint));
                case ASSISTANT -> addAssistantMessage(input, message);
                case SYSTEM -> { /* folded into the instructions above */ }
                case TOOL -> input.add(toolResult(message, i == breakpoint));
            }
        }
        input.add(userMessage(request.getPrompt(), request.getAttachments(), false));
        builder.inputOfResponse(input);

        for (ToolDefinition tool : request.getTools()) {
            builder.addTool(toOpenAiTool(tool));
        }

        if (request.getResponseSchema() != null) {
            builder.text(ResponseTextConfig.builder()
                    .format(ResponseFormatTextJsonSchemaConfig.builder()
                            .name("response")
                            .schema(toJsonSchema(request.getResponseSchema()))
                            .strict(false)
                            .build())
                    .build());
        }

        return builder.build();
    }

    /** The history message that carries the cache breakpoint: the last user message or tool result, or -1. */
    private static int cacheBreakpointIndex(List<Message> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            Role role = history.get(i).getRole();
            if (role == Role.USER || role == Role.TOOL) {
                return i;
            }
        }
        return -1;
    }

    /**
     * A user turn: plain text, or its attachments then its text as content parts. A turn carrying
     * the cache breakpoint is always sent as content parts, the breakpoint on its text.
     */
    private ResponseInputItem userMessage(String text, List<Attachment> attachments, boolean breakpoint) {
        EasyInputMessage.Builder message = EasyInputMessage.builder().role(EasyInputMessage.Role.USER);
        if (attachments.isEmpty() && !breakpoint) {
            return ResponseInputItem.ofEasyInputMessage(message.content(text).build());
        }
        List<ResponseInputContent> parts = new ArrayList<>();
        for (Attachment attachment : attachments) {
            parts.add(toContentPart(attachment));
        }
        ResponseInputText.Builder textPart = ResponseInputText.builder().text(text);
        if (breakpoint) {
            textPart.putAdditionalProperty("prompt_cache_breakpoint", CACHE_BREAKPOINT);
        }
        parts.add(ResponseInputContent.ofInputText(textPart.build()));
        return ResponseInputItem.ofEasyInputMessage(message.contentOfResponseInputMessageContentList(parts).build());
    }

    /**
     * An assistant turn: its text, then one native {@code function_call} item per requested call.
     * A turn that only called tools sends no empty text message.
     */
    private static void addAssistantMessage(List<ResponseInputItem> input, Message message) {
        if (message.getToolCalls().isEmpty() || (message.getContent() != null && !message.getContent().isEmpty())) {
            input.add(ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder()
                    .role(EasyInputMessage.Role.ASSISTANT)
                    .content(message.getContent())
                    .build()));
        }
        for (ToolCall call : message.getToolCalls()) {
            input.add(ResponseInputItem.ofFunctionCall(ResponseFunctionToolCall.builder()
                    .callId(call.getId())
                    .name(call.getName())
                    .arguments(toArgumentsJson(call.getArguments()))
                    .build()));
        }
    }

    /**
     * A native {@code function_call_output} item when the message carries a {@code toolCallId};
     * otherwise a plain flattened user message, exactly as before this correlation support existed.
     * A result carrying the cache breakpoint sends its output as one text block holding it.
     */
    private ResponseInputItem toolResult(Message message, boolean breakpoint) {
        if (message.getToolCallId() == null) {
            return userMessage("Tool result: " + message.getContent(), List.of(), breakpoint);
        }
        ResponseInputItem.FunctionCallOutput.Builder result = ResponseInputItem.FunctionCallOutput.builder()
                .callId(message.getToolCallId());
        if (breakpoint) {
            result.outputOfResponseFunctionCallOutputItemList(List.of(ResponseFunctionCallOutputItem.ofInputText(
                    ResponseInputTextContent.builder()
                            .text(message.getContent())
                            .putAdditionalProperty("prompt_cache_breakpoint", CACHE_BREAKPOINT)
                            .build())));
        } else {
            result.output(message.getContent());
        }
        return ResponseInputItem.ofFunctionCallOutput(result.build());
    }

    private static String toArgumentsJson(Map<String, Object> arguments) {
        try {
            return JSON.writeValueAsString(arguments == null ? Map.of() : arguments);
        } catch (tools.jackson.core.JacksonException e) {
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

    /** The model's maximum output, or the request's {@code maxOutputTokens} when that's lower. */
    private static long resolveMaxTokens(String model, Request request) {
        long modelMax = ModelCapabilityTable.findModel(Provider.OPENAI, model)
                .map(ModelEntry::getMaxOutputTokens)
                .map(Integer::longValue)
                .orElse(DEFAULT_MAX_TOKENS);
        return request.getMaxOutputTokens() == null ? modelMax : Math.min(modelMax, request.getMaxOutputTokens());
    }

    /**
     * The tool as given, with {@code strict: false}: the Responses API makes function tools strict
     * unless told otherwise, and strict mode would need {@code additionalProperties: false} and
     * every property required — the same reason {@link #toJsonSchema} isn't strict.
     */
    private static FunctionTool toOpenAiTool(ToolDefinition tool) {
        FunctionTool.Parameters.Builder parameters = FunctionTool.Parameters.builder();
        if (tool.getParameters() != null) {
            for (Map.Entry<String, Object> entry : tool.getParameters().entrySet()) {
                parameters.putAdditionalProperty(entry.getKey(), JsonValue.from(entry.getValue()));
            }
        }
        return FunctionTool.builder()
                .name(tool.getName())
                .description(tool.getDescription())
                .parameters(parameters.build())
                .strict(false)
                .build();
    }

    /**
     * The schema as given. It's sent with {@code strict: false}, so OpenAI accepts any JSON schema —
     * strict mode would also need {@code additionalProperties: false} on every object and every
     * property in {@code required}, and making optional properties required would change what the
     * caller asked for.
     */
    private static ResponseFormatTextJsonSchemaConfig.Schema toJsonSchema(Map<String, Object> responseSchema) {
        ResponseFormatTextJsonSchemaConfig.Schema.Builder builder = ResponseFormatTextJsonSchemaConfig.Schema.builder();
        for (Map.Entry<String, Object> entry : responseSchema.entrySet()) {
            builder.putAdditionalProperty(entry.getKey(), JsonValue.from(entry.getValue()));
        }
        return builder.build();
    }

    private ResponseInputContent toContentPart(Attachment attachment) {
        String mediaType = attachment.getMediaType();
        if (mediaType != null && mediaType.startsWith("image/")) {
            String base64Data = Base64.getEncoder().encodeToString(attachment.getData());
            return ResponseInputContent.ofInputImage(ResponseInputImage.builder()
                    .detail(ResponseInputImage.Detail.AUTO)
                    .imageUrl("data:" + mediaType + ";base64," + base64Data)
                    .build());
        }

        if (!"application/pdf".equals(ModelEntry.normalizeMediaType(mediaType))) {
            // The capability table lists PDFs only for OpenAI; the router drops anything else for table models (§4).
            throw new IllegalArgumentException("OpenAI accepts PDF documents only, not " + mediaType);
        }
        String filename = attachment.getFilename() == null ? "attachment" : attachment.getFilename();
        String fileId = uploadOrReuseFileId(attachment);
        if (fileId != null) {
            return ResponseInputContent.ofInputFile(ResponseInputFile.builder()
                    .fileId(fileId)
                    .filename(filename)
                    .build());
        }

        // Files API upload unavailable or failed for this attempt — fall back to full inline embedding.
        String base64Data = Base64.getEncoder().encodeToString(attachment.getData());
        return ResponseInputContent.ofInputFile(ResponseInputFile.builder()
                .fileData("data:" + mediaType + ";base64," + base64Data)
                .filename(filename)
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

    /** The text of every output message, in order, and every function call; reasoning items are skipped. */
    private Response fromResponse(com.openai.models.responses.Response response) {
        StringBuilder content = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        for (ResponseOutputItem item : response.output()) {
            if (item.isMessage()) {
                for (ResponseOutputMessage.Content part : item.asMessage().content()) {
                    if (part.isOutputText()) {
                        content.append(part.asOutputText().text());
                    }
                }
            } else if (item.isFunctionCall()) {
                toolCalls.add(toToolCall(item.asFunctionCall()));
            }
        }

        int inputTokens = 0;
        int outputTokens = 0;
        int reasoningTokens = 0;
        int cacheReadTokens = 0;
        int cacheWriteTokens = 0;
        if (response.usage().isPresent()) {
            ResponseUsage usage = response.usage().get();
            inputTokens = (int) usage.inputTokens();
            outputTokens = (int) usage.outputTokens();
            // The details are optional on the wire; read them leniently rather than through the throwing getters.
            reasoningTokens = usage._outputTokensDetails().asKnown()
                    .flatMap(details -> details._reasoningTokens().asKnown()).orElse(0L).intValue();
            cacheReadTokens = usage._inputTokensDetails().asKnown()
                    .flatMap(details -> details._cachedTokens().asKnown()).orElse(0L).intValue();
            cacheWriteTokens = usage._inputTokensDetails().asKnown()
                    .flatMap(details -> details._cacheWriteTokens().asKnown()).orElse(0L).intValue();
        }

        return Response.builder()
                .content(content.toString())
                .toolCalls(toolCalls)
                .usage(io.github.manishpateluk.llmrouter.model.Usage.builder()
                        .inputTokens(inputTokens)
                        .outputTokens(outputTokens)
                        .reasoningTokens(reasoningTokens)
                        .cacheReadTokens(cacheReadTokens)
                        .cacheWriteTokens(cacheWriteTokens)
                        .estimatedCostUsdCents(0) // finalized by the router core against the capability table
                        .build())
                .original(response)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static ToolCall toToolCall(ResponseFunctionToolCall call) {
        String arguments = call.arguments();
        return ToolCall.builder()
                .id(call.callId())
                .name(call.name())
                .arguments(arguments.isBlank() ? Map.of() : (Map<String, Object>) JSON.readValue(arguments, Map.class))
                .build();
    }
}
