package io.github.manishpateluk.llmrouter.provider.anthropic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.ErrorType;
import com.anthropic.models.files.FileUploadParams;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.Base64PdfSource;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DocumentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.PlainTextSource;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.ToolUseBlockParam;
import io.github.manishpateluk.llmrouter.capability.ModelCapabilityTable;
import io.github.manishpateluk.llmrouter.capability.ModelEntry;
import io.github.manishpateluk.llmrouter.config.StructuredOutputStrategy;
import io.github.manishpateluk.llmrouter.model.Attachment;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.model.Role;
import io.github.manishpateluk.llmrouter.model.ToolCall;
import io.github.manishpateluk.llmrouter.model.ToolDefinition;
import io.github.manishpateluk.llmrouter.negotiation.CapabilityNegotiator;
import io.github.manishpateluk.llmrouter.provider.Provider;
import io.github.manishpateluk.llmrouter.provider.ProviderAdapter;
import io.github.manishpateluk.llmrouter.provider.TransientFailures;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ProviderAdapter} for Anthropic (Claude), built on the official {@code anthropic-java}
 * SDK. {@link #sendAsync} uses the SDK's own {@code client.async()} view, which is genuinely
 * non-blocking — not the interface's thread-pool fallback.
 *
 * <p>Known scope limits (documented rather than silently guessed at): a history turn is sent
 * using Anthropic's native {@code tool_use}/{@code tool_result} content blocks only when the
 * caller populated {@code Message.toolCalls}/{@code toolCallId} (§3); without those, a {@code
 * TOOL}-role history entry falls back to a plain user-role message instead. Documents are sent as
 * {@code document} blocks for {@code application/pdf} and {@code text/plain} only — the two types
 * Anthropic accepts; any other non-image type fails the attempt (the router drops such
 * attachments before they get here for models in the capability table); {@code
 * Response.generatedFiles} is left empty, since nothing
 * in a plain request causes Claude to produce a file without the caller also requesting a
 * code-execution-capable tool, which this adapter doesn't add implicitly.
 *
 * <p>Repeated-attachment optimization: an attachment is opportunistically uploaded once via
 * Anthropic's Files API and referenced by file id on every subsequent call whose attachment has
 * byte-identical content — instead of re-embedding and re-transmitting the same base64 payload
 * on every turn of a multi-turn conversation. The upload cache is keyed by a content hash
 * ({@code mediaType} + bytes) and scoped to this adapter instance — it is never shared across
 * providers or across separate {@code AnthropicAdapter} instances, so this can't leak a stale
 * reference into a fallback attempt on a different provider. This is a pure optimization, never a
 * correctness dependency: if the upload call
 * itself fails for any reason (network blip, permissions, size limits), that one attachment
 * transparently falls back to full inline embedding for the current attempt, and the failure is
 * not cached — the next call retries the upload rather than giving up on the optimization
 * permanently. The cache itself has no eviction or TTL, so an adapter instance that lives a very
 * long time and sees a very large number of distinct attachments will grow it unboundedly; fine
 * for the common case (one adapter instance serving one, or a modest number of, conversations)
 * but worth knowing for a long-lived, high-attachment-cardinality server process.
 *
 * <p>Prompt caching (§4.3): unless {@code RouterConfig.promptCaching} is off, the request carries
 * at most two {@code cache_control} breakpoints, well inside Anthropic's limit of four. The first
 * ends the part that repeats across calls: the system instructions, or the last tool definition
 * when there are none. The second ends the conversation when there's history, so the next turn
 * reads everything so far from the cache; without history it ends the attachments, if any. A
 * prefix shorter than the model's minimum (1,024 tokens or more, depending on the model) simply
 * isn't cached. Cache writes cost 1.25 times the input price and reads a tenth of it.
 *
 * <p>Schemas too large for Anthropic's strict grammar: Anthropic compiles a {@code responseSchema}
 * into a grammar and rejects one whose grammar is too large (a 400 {@code invalid_request_error}
 * saying "compiled grammar is too large"). That depends on the whole schema's size, so
 * {@link #responseSchemaProblem} can't predict it. Unless the request's
 * {@code structuredOutputStrategy} is {@code NATIVE}, the call is retried once on the same model
 * with the schema in the prompt, as {@code PROMPT_FALLBACK} sends it; the reply must then parse as
 * JSON and fit the schema, or the attempt fails. The outcome is remembered per (model, schema) for
 * this adapter's life, so later calls with that schema go straight to the prompt. A WARN is logged
 * once per schema.
 */
public final class AnthropicAdapter implements ProviderAdapter {

    private static final Logger log = LoggerFactory.getLogger(AnthropicAdapter.class);
    private static final long DEFAULT_MAX_TOKENS = 4096L;
    private static final CacheControlEphemeral CACHE_BREAKPOINT = CacheControlEphemeral.builder().build();
    private static final String GRAMMAR_TOO_LARGE = "compiled grammar is too large";
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private final AnthropicClient client;
    private final Map<String, String> uploadedFileIdsByContentHash = new ConcurrentHashMap<>();
    /** {@code model + " " + schema hash} for each schema Anthropic's grammar rejected as too large. */
    private final Set<String> schemasTooLargeForGrammar = ConcurrentHashMap.newKeySet();
    private final Set<String> warnedSchemaHashes = ConcurrentHashMap.newKeySet();

    /**
     * Builds its own client from {@code apiKey}; {@link #isAvailable()} is {@code false} if it's
     * null/blank. The client makes no retries of its own: retrying is the router's job, under
     * {@code RouterConfig.maxRetries} (§5.5).
     */
    public AnthropicAdapter(String apiKey) {
        this(newClient(apiKey, null));
    }

    /** The client {@link #AnthropicAdapter(String)} builds, or {@code null} without a key; {@code baseUrl} {@code null} for Anthropic's own. */
    static AnthropicClient newClient(String apiKey, String baseUrl) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder().apiKey(apiKey).maxRetries(0);
        if (baseUrl != null) {
            builder.baseUrl(baseUrl);
        }
        return builder.build();
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

    /** Also recognises the SDK's own HTTP and I/O exceptions; see {@link ProviderAdapter#isTransient}. */
    @Override
    public boolean isTransient(Throwable failure) {
        AnthropicServiceException service = TransientFailures.find(failure, AnthropicServiceException.class);
        if (service != null) {
            return TransientFailures.isTransientStatus(service.statusCode());
        }
        return TransientFailures.find(failure, AnthropicIoException.class) != null || ProviderAdapter.super.isTransient(failure);
    }

    @Override
    public Duration retryAfter(Throwable failure) {
        AnthropicServiceException service = TransientFailures.find(failure, AnthropicServiceException.class);
        return service == null ? null : TransientFailures.retryAfter(name -> service.headers().values(name));
    }

    /** What {@link AnthropicSchemas} can't turn into a schema Anthropic's structured outputs accept, if anything. */
    @Override
    public String responseSchemaProblem(String model, Map<String, Object> responseSchema) {
        return AnthropicSchemas.convert(responseSchema).problem();
    }

    @Override
    public Response send(String model, Request adaptedRequest) {
        requireAvailable();
        return withGrammarFallback(model, adaptedRequest,
                request -> fromMessage(client.messages().create(toParams(model, request))));
    }

    @Override
    public CompletableFuture<Response> sendAsync(String model, Request adaptedRequest) {
        requireAvailable();
        Function<Request, CompletableFuture<Response>> sender =
                request -> client.async().messages().create(toParams(model, request)).thenApply(this::fromMessage);
        if (sendsSchemaInPrompt(model, adaptedRequest)) {
            return sendWithSchemaInPrompt(adaptedRequest, sender);
        }
        return sender.apply(adaptedRequest).exceptionallyCompose(failure -> {
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
            return switchesToPrompt(model, adaptedRequest, cause)
                    ? sendWithSchemaInPrompt(adaptedRequest, sender)
                    : CompletableFuture.failedFuture(cause);
        });
    }

    /** Streams via the Messages API's server-sent events, accumulating them into the same {@link Message} {@link #send} would return. */
    @Override
    public Response sendStreaming(String model, Request adaptedRequest, Consumer<String> onText) {
        requireAvailable();
        return withGrammarFallback(model, adaptedRequest, request -> stream(model, request, onText));
    }

    private Response stream(String model, Request request, Consumer<String> onText) {
        MessageAccumulator accumulator = MessageAccumulator.create();
        try (StreamResponse<RawMessageStreamEvent> stream = client.messages().createStreaming(toParams(model, request))) {
            stream.stream().forEach(event -> {
                accumulator.accumulate(event);
                event.contentBlockDelta()
                        .flatMap(delta -> delta.delta().text())
                        .ifPresent(text -> onText.accept(text.text()));
            });
        }
        return fromMessage(accumulator.message());
    }

    /**
     * Sends {@code request}; if Anthropic rejects its schema as too large for its grammar, sends it
     * again with the schema in the prompt (see the class javadoc).
     */
    private Response withGrammarFallback(String model, Request request, Function<Request, Response> sender) {
        if (sendsSchemaInPrompt(model, request)) {
            return checkedPromptReply(request, sender.apply(CapabilityNegotiator.withSchemaInPrompt(request)));
        }
        try {
            return sender.apply(request);
        } catch (RuntimeException e) {
            if (!switchesToPrompt(model, request, e)) {
                throw e;
            }
            return checkedPromptReply(request, sender.apply(CapabilityNegotiator.withSchemaInPrompt(request)));
        }
    }

    private static CompletableFuture<Response> sendWithSchemaInPrompt(
            Request request, Function<Request, CompletableFuture<Response>> sender) {
        return sender.apply(CapabilityNegotiator.withSchemaInPrompt(request))
                .thenApply(reply -> checkedPromptReply(request, reply));
    }

    /** Whether an earlier call already found this request's schema too large for {@code model}'s grammar. */
    private boolean sendsSchemaInPrompt(String model, Request request) {
        return request.getResponseSchema() != null && !nativeOnly(request) && !schemasTooLargeForGrammar.isEmpty()
                && schemasTooLargeForGrammar.contains(model + " " + schemaHash(request.getResponseSchema()));
    }

    /**
     * Whether {@code failure} is Anthropic rejecting this request's schema as too large for its
     * grammar, and the request's strategy allows the prompt instead; if so, remembers it.
     */
    private boolean switchesToPrompt(String model, Request request, Throwable failure) {
        if (request.getResponseSchema() == null || nativeOnly(request) || !isGrammarTooLarge(failure)) {
            return false;
        }
        String hash = schemaHash(request.getResponseSchema());
        schemasTooLargeForGrammar.add(model + " " + hash);
        if (warnedSchemaHashes.add(hash)) {
            log.warn("Schema too large for Anthropic's strict grammar; answering with the schema in the prompt instead "
                    + "(model {}, schema {})", model, hash.substring(0, 12));
        }
        return true;
    }

    private static boolean nativeOnly(Request request) {
        return request.getConfig() != null
                && request.getConfig().getStructuredOutputStrategy() == StructuredOutputStrategy.NATIVE;
    }

    private static boolean isGrammarTooLarge(Throwable failure) {
        return failure instanceof AnthropicServiceException e
                && e.statusCode() == 400
                && e.errorType().map(ErrorType.INVALID_REQUEST_ERROR::equals).orElse(false)
                && String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT).contains(GRAMMAR_TOO_LARGE);
    }

    /**
     * {@code reply} to a request whose schema went in the prompt, if its content is JSON that fits
     * the schema; otherwise fails the attempt, so the router's usual fallback rules apply.
     */
    private static Response checkedPromptReply(Request request, Response reply) {
        Object parsed;
        try {
            parsed = JSON.readValue(reply.getContent(), new TypeReference<Object>() {
            });
        } catch (JacksonException e) {
            throw new IllegalStateException("Structured output failed: the reply to the schema in the prompt isn't valid JSON ("
                    + e.getOriginalMessage() + ")", e);
        }
        String problem = ReplySchemaCheck.problem(request.getResponseSchema(), parsed);
        if (problem != null) {
            throw new IllegalStateException("Structured output failed: the reply to the schema in the prompt doesn't match it: "
                    + problem);
        }
        return reply;
    }

    private static String schemaHash(Map<String, Object> schema) {
        try {
            return sha256(JSON.writeValueAsBytes(schema));
        } catch (JacksonException e) {
            throw new IllegalArgumentException("responseSchema could not be serialized to JSON", e);
        }
    }

    private void requireAvailable() {
        if (client == null) {
            throw new IllegalStateException("AnthropicAdapter has no credentials configured");
        }
    }

    private MessageCreateParams toParams(String model, Request request) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(resolveMaxTokens(model, request));

        if (request.getTemperature() != null) {
            builder.temperature(request.getTemperature());
        }
        if (request.getTopP() != null) {
            builder.topP(request.getTopP());
        }

        boolean caching = request.getConfig() == null || request.getConfig().isPromptCaching();
        String system = buildSystemInstructions(request);
        if (system != null) {
            TextBlockParam systemBlock = TextBlockParam.builder().text(system).build();
            builder.systemOfTextBlockParams(List.of(caching ? systemBlock.toBuilder().cacheControl(CACHE_BREAKPOINT).build() : systemBlock));
        }

        for (var message : request.getHistory()) {
            switch (message.getRole()) {
                case USER -> builder.addUserMessage(message.getContent());
                case ASSISTANT -> addAssistantMessage(builder, message);
                case SYSTEM -> { /* folded into system instructions above */ }
                case TOOL -> addToolResultMessage(builder, message);
            }
        }

        // Breakpoint two: the end of the conversation when there's history, else the end of the attachments.
        boolean cacheConversation = !request.getHistory().isEmpty();
        List<Attachment> attachments = request.getAttachments();
        if (attachments.isEmpty() && !(caching && cacheConversation)) {
            builder.addUserMessage(request.getPrompt());
        } else {
            List<ContentBlockParam> blocks = new ArrayList<>();
            for (Attachment attachment : attachments) {
                blocks.add(toContentBlockParam(attachment));
            }
            blocks.add(ContentBlockParam.ofText(request.getPrompt()));
            if (caching) {
                // the prompt when caching the conversation, otherwise the last attachment
                int breakpoint = cacheConversation ? blocks.size() - 1 : blocks.size() - 2;
                blocks.set(breakpoint, withCacheBreakpoint(blocks.get(breakpoint)));
            }
            builder.addUserMessageOfBlockParams(blocks);
        }

        // Breakpoint one: the system instructions above, or failing those the last tool (tools come first in the prefix).
        List<ToolDefinition> tools = request.getTools();
        for (int i = 0; i < tools.size(); i++) {
            Tool tool = toAnthropicTool(tools.get(i));
            boolean breakpoint = caching && system == null && i == tools.size() - 1;
            builder.addTool(breakpoint ? tool.toBuilder().cacheControl(CACHE_BREAKPOINT).build() : tool);
        }

        if (request.getResponseSchema() != null || request.getReasoningEffort() != null) {
            OutputConfig.Builder outputConfig = OutputConfig.builder();
            if (request.getResponseSchema() != null) {
                outputConfig.format(JsonOutputFormat.builder().schema(toOutputSchema(request.getResponseSchema())).build());
            }
            if (request.getReasoningEffort() != null) {
                outputConfig.effort(OutputConfig.Effort.of(request.getReasoningEffort()));
            }
            builder.outputConfig(outputConfig.build());
        }
        if (request.getReasoningBudgetTokens() != null) {
            builder.enabledThinking(request.getReasoningBudgetTokens());
        }

        return builder.build();
    }

    /**
     * Sends {@code message} as a native assistant turn: a text block (if it has non-blank
     * content) followed by one {@code tool_use} block per requested call. Falls back to a plain
     * assistant text message when {@code toolCalls} is empty (the common, no-tool-call case).
     */
    private static void addAssistantMessage(MessageCreateParams.Builder builder, io.github.manishpateluk.llmrouter.model.Message message) {
        if (message.getToolCalls().isEmpty()) {
            builder.addAssistantMessage(message.getContent());
            return;
        }

        List<ContentBlockParam> blocks = new ArrayList<>();
        if (message.getContent() != null && !message.getContent().isBlank()) {
            blocks.add(ContentBlockParam.ofText(message.getContent()));
        }
        for (ToolCall call : message.getToolCalls()) {
            blocks.add(ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                    .id(call.getId())
                    .name(call.getName())
                    .input(toToolUseInput(call.getArguments()))
                    .build()));
        }
        builder.addAssistantMessageOfBlockParams(blocks);
    }

    /**
     * Sends {@code message} as a native {@code tool_result} block — as a user-role message, per
     * Anthropic's own convention — when it carries a {@code toolCallId}; otherwise falls back to
     * a plain flattened user message, exactly as before this correlation support existed.
     */
    private static void addToolResultMessage(MessageCreateParams.Builder builder, io.github.manishpateluk.llmrouter.model.Message message) {
        if (message.getToolCallId() == null) {
            builder.addUserMessage("Tool result: " + message.getContent());
            return;
        }
        builder.addUserMessageOfBlockParams(List.of(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                .toolUseId(message.getToolCallId())
                .content(message.getContent())
                .build())));
    }

    private static ToolUseBlockParam.Input toToolUseInput(Map<String, Object> arguments) {
        ToolUseBlockParam.Input.Builder input = ToolUseBlockParam.Input.builder();
        if (arguments != null) {
            for (Map.Entry<String, Object> entry : arguments.entrySet()) {
                input.putAdditionalProperty(entry.getKey(), JsonValue.from(entry.getValue()));
            }
        }
        return input.build();
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
        long modelMax = ModelCapabilityTable.findModel(Provider.ANTHROPIC, model)
                .map(ModelEntry::getMaxOutputTokens)
                .map(Integer::longValue)
                .orElse(DEFAULT_MAX_TOKENS);
        return request.getMaxOutputTokens() == null ? modelMax : Math.min(modelMax, request.getMaxOutputTokens());
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

    /**
     * The request's schema as Anthropic accepts it — see {@link AnthropicSchemas}: a copy with
     * {@code additionalProperties: false} on every object, and unenforceable constraints moved into
     * descriptions. A schema it can't express fails here, naming the path; the router never sends
     * one (see {@link #responseSchemaProblem}), so this only happens when the adapter is called directly.
     */
    private static JsonOutputFormat.Schema toOutputSchema(Map<String, Object> responseSchema) {
        AnthropicSchemas.Conversion conversion = AnthropicSchemas.convert(responseSchema);
        if (conversion.problem() != null) {
            throw new IllegalArgumentException("responseSchema can't be enforced by Anthropic structured outputs: "
                    + conversion.problem());
        }
        JsonOutputFormat.Schema.Builder schema = JsonOutputFormat.Schema.builder();
        for (Map.Entry<String, Object> entry : conversion.schema().entrySet()) {
            schema.putAdditionalProperty(entry.getKey(), JsonValue.from(entry.getValue()));
        }
        return schema.build();
    }

    /** {@code block} with a prompt-cache breakpoint on it; only text, image and document blocks carry one here. */
    private static ContentBlockParam withCacheBreakpoint(ContentBlockParam block) {
        if (block.isText()) {
            return ContentBlockParam.ofText(block.asText().toBuilder().cacheControl(CACHE_BREAKPOINT).build());
        }
        if (block.isImage()) {
            return ContentBlockParam.ofImage(block.asImage().toBuilder().cacheControl(CACHE_BREAKPOINT).build());
        }
        if (block.isDocument()) {
            return ContentBlockParam.ofDocument(block.asDocument().toBuilder().cacheControl(CACHE_BREAKPOINT).build());
        }
        throw new IllegalArgumentException("No prompt-cache breakpoint for this block: " + block);
    }

    private ContentBlockParam toContentBlockParam(Attachment attachment) {
        String mediaType = attachment.getMediaType();
        String normalized = ModelEntry.normalizeMediaType(mediaType);
        boolean isImage = normalized.startsWith("image/");
        if (!isImage && !normalized.equals("application/pdf") && !normalized.equals("text/plain")) {
            throw new IllegalArgumentException("Anthropic accepts PDF and plain-text documents only, not " + mediaType);
        }
        String fileId = uploadOrReuseFileId(attachment);
        if (fileId != null) {
            return isImage
                    ? ContentBlockParam.ofImage(ImageBlockParam.builder().fileSource(fileId).build())
                    : ContentBlockParam.ofDocument(DocumentBlockParam.builder().fileSource(fileId).build());
        }

        // Files API upload unavailable or failed for this attempt — fall back to full inline embedding.
        String base64Data = Base64.getEncoder().encodeToString(attachment.getData());
        if (isImage) {
            return ContentBlockParam.ofImage(ImageBlockParam.builder()
                    .source(Base64ImageSource.builder()
                            .mediaType(Base64ImageSource.MediaType.of(mediaType))
                            .data(base64Data)
                            .build())
                    
                    .build());
        }
        if (normalized.equals("text/plain")) {
            return ContentBlockParam.ofDocument(DocumentBlockParam.builder()
                    .source(PlainTextSource.builder().data(new String(attachment.getData(), StandardCharsets.UTF_8)).build())
                    
                    .build());
        }
        return ContentBlockParam.ofDocument(DocumentBlockParam.builder()
                .source(Base64PdfSource.builder().data(base64Data).build())
                
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
                return client.files().upload(FileUploadParams.builder().file(attachment.getData()).build()).id();
            } catch (RuntimeException e) {
                log.warn("Attachment upload to Anthropic Files API failed; falling back to inline embedding "
                        + "for this attempt: {}", e.getMessage());
                return null;
            }
        });
    }

    private static String contentHash(Attachment attachment) {
        byte[] mediaType = String.valueOf(attachment.getMediaType()).getBytes(StandardCharsets.UTF_8);
        return sha256(mediaType, new byte[] {0}, attachment.getData());
    }

    private static String sha256(byte[]... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] part : parts) {
                digest.update(part);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
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

        // Anthropic's input_tokens leaves out cached tokens; Usage.inputTokens counts every input token.
        int cacheRead = (int) message.usage().cacheReadInputTokens().orElse(0L).longValue();
        int cacheWrite = (int) message.usage().cacheCreationInputTokens().orElse(0L).longValue();
        return Response.builder()
                .content(content.toString())
                .toolCalls(toolCalls)
                .usage(io.github.manishpateluk.llmrouter.model.Usage.builder()
                        .inputTokens((int) message.usage().inputTokens() + cacheRead + cacheWrite)
                        .outputTokens((int) message.usage().outputTokens())
                        .reasoningTokens(0) // Anthropic counts thinking in output_tokens without breaking it out
                        .cacheReadTokens(cacheRead)
                        .cacheWriteTokens(cacheWrite)
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
