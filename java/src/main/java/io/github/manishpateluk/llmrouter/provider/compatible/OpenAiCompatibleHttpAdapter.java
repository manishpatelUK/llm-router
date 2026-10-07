package io.github.manishpateluk.llmrouter.provider.compatible;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import io.github.manishpateluk.llmrouter.capability.ModelCapabilityTable;
import io.github.manishpateluk.llmrouter.capability.ModelEntry;
import io.github.manishpateluk.llmrouter.model.Attachment;
import io.github.manishpateluk.llmrouter.model.Citation;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.model.Role;
import io.github.manishpateluk.llmrouter.model.ToolCall;
import io.github.manishpateluk.llmrouter.model.ToolDefinition;
import io.github.manishpateluk.llmrouter.model.Usage;
import io.github.manishpateluk.llmrouter.provider.ProviderAdapter;
import io.github.manishpateluk.llmrouter.provider.compatible.HttpTransport.HttpRequestRecord;
import io.github.manishpateluk.llmrouter.provider.compatible.HttpTransport.HttpResponseRecord;

/**
 * Shared base for providers with no official Java SDK that expose an OpenAI-compatible
 * chat/completions HTTP endpoint (Perplexity, NVIDIA NIM, Hugging Face, OpenRouter) — see
 * {@code MODEL_CAPABILITY_HEURISTICS.md}'s scoping notes and this project's implementation plan
 * for why raw HTTP was chosen over borrowing the official OpenAI SDK against a foreign vendor's
 * API. Subclasses supply only the endpoint URL and, if needed, a non-default auth header shape.
 *
 * <p>Known scope limits (documented rather than silently guessed at): the unified
 * {@code Message} history shape (§3) carries no tool-call id, so a {@code TOOL}-role history
 * entry is sent as a plain user-role message; {@code image/*} attachments are mapped to the
 * {@code image_url} content-part convention shared by every OpenAI-compatible API, while non-image
 * document attachments are sent only by subclasses that opt in via
 * {@link #supportsDocumentAttachments()} (OpenRouter's {@code file} content part) — the others
 * document no convention for them, so documents aren't sent there; {@code Response.generatedFiles} is
 * always left empty, since none of these four providers document a file-generation mechanism.
 * Unlike {@code AnthropicAdapter}/{@code OpenAiAdapter}, attachments here are always re-embedded
 * inline on every call — none of these four vendors' OpenAI-compatible endpoints share a common,
 * documented file-upload-and-reference convention this shared base could target generically, so
 * no attempt is made to deduplicate repeated attachment content across calls for them.
 */
public abstract class OpenAiCompatibleHttpAdapter implements ProviderAdapter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long DEFAULT_MAX_TOKENS = 4096L;

    private final String apiKey;
    private final HttpTransport transport;

    protected OpenAiCompatibleHttpAdapter(String apiKey) {
        this(apiKey, new JdkHttpTransport());
    }

    protected OpenAiCompatibleHttpAdapter(String apiKey, HttpTransport transport) {
        this.apiKey = (apiKey == null || apiKey.isBlank()) ? null : apiKey;
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
    }

    /** Full URL of this provider's OpenAI-compatible chat/completions endpoint. */
    protected abstract String chatCompletionsUrl();

    /** Auth header(s) to send, given the resolved API key. Default: {@code Authorization: Bearer <key>}. */
    protected Map<String, String> authHeaders(String resolvedApiKey) {
        return Map.of("Authorization", "Bearer " + resolvedApiKey);
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null;
    }

    @Override
    public Response send(String model, Request adaptedRequest) {
        requireAvailable();
        return parseResponse(transport.send(buildHttpRequest(model, adaptedRequest)));
    }

    @Override
    public CompletableFuture<Response> sendAsync(String model, Request adaptedRequest) {
        requireAvailable();
        return transport.sendAsync(buildHttpRequest(model, adaptedRequest)).thenApply(this::parseResponse);
    }

    private void requireAvailable() {
        if (apiKey == null) {
            throw new IllegalStateException(getClass().getSimpleName() + " has no credentials configured");
        }
    }

    /**
     * Streams the chat completion as server-sent events: each text delta goes to {@code onText}
     * as it arrives, while tool-call fragments and usage are accumulated into the same response
     * fragment {@link #send} would return. {@code original} holds every streamed chunk, in order.
     */
    @Override
    public Response sendStreaming(String model, Request adaptedRequest, Consumer<String> onText) {
        requireAvailable();
        ObjectNode body = buildBody(model, adaptedRequest);
        body.put("stream", true);
        if (requestsStreamUsage()) {
            body.putObject("stream_options").put("include_usage", true);
        }
        StreamAccumulator accumulator = new StreamAccumulator(onText);
        HttpResponseRecord status = transport.sendStreaming(toHttpRequest(body), accumulator::acceptLine);
        if (status.statusCode() >= 300) {
            throw new ProviderHttpException(status.statusCode(), status.body());
        }
        return accumulator.toResponse();
    }

    /**
     * Whether a streaming request should ask for token usage in the final chunk via the
     * OpenAI-standard {@code stream_options: {"include_usage": true}}. On by default; a provider
     * that doesn't document the option overrides this to {@code false}, and its usage is then
     * taken from whatever chunk reports it, or left at zero if none does.
     */
    protected boolean requestsStreamUsage() {
        return true;
    }

    private HttpRequestRecord buildHttpRequest(String model, Request request) {
        return toHttpRequest(buildBody(model, request));
    }

    private HttpRequestRecord toHttpRequest(ObjectNode body) {
        Map<String, String> headers = new LinkedHashMap<>(authHeaders(apiKey));
        headers.put("Content-Type", "application/json");
        return new HttpRequestRecord(chatCompletionsUrl(), headers, body.toString());
    }

    private ObjectNode buildBody(String model, Request request) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.set("messages", buildMessages(request));
        body.put("max_tokens", resolveMaxTokens(model));

        if (request.getTemperature() != null) {
            body.put("temperature", request.getTemperature());
        }
        if (request.getTopP() != null) {
            body.put("top_p", request.getTopP());
        }

        if (!request.getTools().isEmpty()) {
            body.set("tools", buildTools(request.getTools()));
        }
        if (request.getResponseSchema() != null) {
            body.set("response_format", buildResponseFormat(request.getResponseSchema()));
        }

        return body;
    }

    private ArrayNode buildMessages(Request request) {
        ArrayNode messages = MAPPER.createArrayNode();

        String system = buildSystemInstructions(request);
        if (system != null) {
            messages.add(simpleMessage("system", system));
        }

        for (var message : request.getHistory()) {
            switch (message.getRole()) {
                case USER -> messages.add(simpleMessage("user", message.getContent()));
                case ASSISTANT -> messages.add(simpleMessage("assistant", message.getContent()));
                case SYSTEM -> { /* folded into system instructions above */ }
                case TOOL -> messages.add(simpleMessage("user", "Tool result: " + message.getContent()));
            }
        }

        List<Attachment> imageAttachments = request.getAttachments().stream()
                .filter(OpenAiCompatibleHttpAdapter::isImage)
                .toList();
        List<Attachment> documentAttachments = supportsDocumentAttachments()
                ? request.getAttachments().stream().filter(a -> !isImage(a)).toList()
                : List.of();
        if (imageAttachments.isEmpty() && documentAttachments.isEmpty()) {
            messages.add(simpleMessage("user", request.getPrompt()));
        } else {
            messages.add(userMessageWithAttachments(request.getPrompt(), imageAttachments, documentAttachments));
        }

        return messages;
    }

    /**
     * Whether this provider accepts non-image document attachments (PDFs etc.) as OpenRouter-style
     * {@code {"type": "file", "file": {"filename", "file_data"}}} content parts. Off by default:
     * only override it for a provider that documents that convention, since the others would
     * reject or ignore the part.
     */
    protected boolean supportsDocumentAttachments() {
        return false;
    }

    private static boolean isImage(Attachment attachment) {
        return attachment.getMediaType() != null && attachment.getMediaType().startsWith("image/");
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

    private ObjectNode simpleMessage(String role, String content) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("role", role);
        node.put("content", content);
        return node;
    }

    private ObjectNode userMessageWithAttachments(String prompt, List<Attachment> images, List<Attachment> documents) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("role", "user");
        ArrayNode parts = node.putArray("content");

        ObjectNode textPart = MAPPER.createObjectNode();
        textPart.put("type", "text");
        textPart.put("text", prompt);
        parts.add(textPart);

        for (Attachment image : images) {
            ObjectNode imagePart = MAPPER.createObjectNode();
            imagePart.put("type", "image_url");
            ObjectNode imageUrl = imagePart.putObject("image_url");
            String base64Data = Base64.getEncoder().encodeToString(image.getData());
            imageUrl.put("url", "data:" + image.getMediaType() + ";base64," + base64Data);
            parts.add(imagePart);
        }

        for (int i = 0; i < documents.size(); i++) {
            Attachment document = documents.get(i);
            ObjectNode filePart = MAPPER.createObjectNode();
            filePart.put("type", "file");
            ObjectNode file = filePart.putObject("file");
            file.put("filename", document.getFilename() != null ? document.getFilename() : "document-" + (i + 1));
            String base64Data = Base64.getEncoder().encodeToString(document.getData());
            file.put("file_data", "data:" + document.getMediaType() + ";base64," + base64Data);
            parts.add(filePart);
        }

        return node;
    }

    private ArrayNode buildTools(List<ToolDefinition> tools) {
        ArrayNode toolsNode = MAPPER.createArrayNode();
        for (ToolDefinition tool : tools) {
            ObjectNode toolNode = MAPPER.createObjectNode();
            toolNode.put("type", "function");
            ObjectNode function = toolNode.putObject("function");
            function.put("name", tool.getName());
            function.put("description", tool.getDescription());
            function.set("parameters", MAPPER.valueToTree(tool.getParameters()));
            toolsNode.add(toolNode);
        }
        return toolsNode;
    }

    private ObjectNode buildResponseFormat(Map<String, Object> responseSchema) {
        ObjectNode format = MAPPER.createObjectNode();
        format.put("type", "json_schema");
        ObjectNode jsonSchema = format.putObject("json_schema");
        jsonSchema.put("name", "response");
        jsonSchema.set("schema", MAPPER.valueToTree(responseSchema));
        return format;
    }

    private long resolveMaxTokens(String model) {
        return ModelCapabilityTable.findModel(id(), model)
                .map(ModelEntry::getMaxOutputTokens)
                .map(Integer::longValue)
                .orElse(DEFAULT_MAX_TOKENS);
    }

    private Response parseResponse(HttpResponseRecord httpResponse) {
        if (httpResponse.statusCode() >= 300) {
            throw new ProviderHttpException(httpResponse.statusCode(), httpResponse.body());
        }

        JsonNode root = readTree(httpResponse.body());
        JsonNode message = root.path("choices").path(0).path("message");

        return Response.builder()
                .content(message.path("content").asText(""))
                .toolCalls(parseToolCalls(message.path("tool_calls")))
                .usage(Usage.builder()
                        .inputTokens(root.path("usage").path("prompt_tokens").asInt(0))
                        .outputTokens(root.path("usage").path("completion_tokens").asInt(0))
                        .reasoningTokens(0)
                        .estimatedCostUsdCents(0) // finalized by the router core against the capability table
                        .build())
                .citations(parseCitations(root))
                .original(root)
                .build();
    }

    /**
     * Reads the top-level {@code citations} (source URLs, in the order the answer's {@code [n]}
     * markers number them) and {@code search_results} (title, url, snippet per source) that
     * Perplexity adds to its responses. The result follows {@code citations}' order, enriched
     * from the matching search result; with only {@code search_results}, their order is used.
     * Providers that send neither get an empty list.
     */
    private static List<Citation> parseCitations(JsonNode body) {
        Map<String, JsonNode> searchResultsByUrl = new LinkedHashMap<>();
        for (JsonNode result : body.path("search_results")) {
            String url = result.path("url").asText("");
            if (!url.isEmpty()) {
                searchResultsByUrl.putIfAbsent(url, result);
            }
        }
        List<String> urls = new ArrayList<>();
        for (JsonNode url : body.path("citations")) {
            if (!url.asText("").isEmpty()) {
                urls.add(url.asText());
            }
        }
        if (urls.isEmpty()) {
            urls.addAll(searchResultsByUrl.keySet());
        }
        List<Citation> citations = new ArrayList<>();
        for (String url : urls) {
            JsonNode result = searchResultsByUrl.get(url);
            citations.add(Citation.builder()
                    .url(url)
                    .title(result == null ? null : nonBlank(result.path("title")))
                    .snippet(result == null ? null : nonBlank(result.path("snippet")))
                    .build());
        }
        return citations;
    }

    private static String nonBlank(JsonNode text) {
        String value = text.asText("");
        return value.isBlank() ? null : value;
    }

    /**
     * Folds a chat-completions SSE stream back into one response: {@code data:} lines carry JSON
     * chunks, {@code data: [DONE]} ends the stream, and anything else (blank separators, {@code :}
     * keep-alive comments) is ignored. Tool calls arrive as fragments keyed by {@code index}, with
     * the id and name in the first fragment and the arguments JSON split across the rest.
     */
    private final class StreamAccumulator {

        private final Consumer<String> onText;
        private final StringBuilder content = new StringBuilder();
        private final Map<Integer, StreamedToolCall> toolCalls = new TreeMap<>();
        private final ArrayNode chunks = MAPPER.createArrayNode();
        private JsonNode usage = MAPPER.missingNode();
        private List<Citation> citations = List.of();

        StreamAccumulator(Consumer<String> onText) {
            this.onText = onText;
        }

        void acceptLine(String line) {
            if (!line.startsWith("data:")) {
                return;
            }
            String data = line.substring("data:".length()).trim();
            if (data.isEmpty() || data.equals("[DONE]")) {
                return;
            }
            JsonNode chunk = readTree(data);
            chunks.add(chunk);
            if (chunk.has("error")) {
                throw new IllegalStateException("Provider reported an error mid-stream: " + chunk.path("error"));
            }
            if (chunk.path("usage").isObject()) {
                usage = chunk.path("usage");
            }
            List<Citation> chunkCitations = parseCitations(chunk);
            if (!chunkCitations.isEmpty()) {
                citations = chunkCitations; // Perplexity repeats them on chunks; the latest list is the complete one
            }
            JsonNode delta = chunk.path("choices").path(0).path("delta");
            String text = delta.path("content").asText("");
            if (!text.isEmpty()) {
                content.append(text);
                onText.accept(text);
            }
            for (JsonNode fragment : delta.path("tool_calls")) {
                toolCalls.computeIfAbsent(fragment.path("index").asInt(toolCalls.size()), i -> new StreamedToolCall())
                        .accept(fragment);
            }
        }

        Response toResponse() {
            return Response.builder()
                    .content(content.toString())
                    .toolCalls(toolCalls.values().stream().map(StreamedToolCall::toToolCall).toList())
                    .usage(Usage.builder()
                            .inputTokens(usage.path("prompt_tokens").asInt(0))
                            .outputTokens(usage.path("completion_tokens").asInt(0))
                            .reasoningTokens(0)
                            .estimatedCostUsdCents(0) // finalized by the router core against the capability table
                            .build())
                    .citations(citations)
                    .original(chunks)
                    .build();
        }
    }

    private final class StreamedToolCall {

        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        void accept(JsonNode fragment) {
            if (fragment.hasNonNull("id")) {
                id = fragment.path("id").asText();
            }
            JsonNode function = fragment.path("function");
            if (function.hasNonNull("name")) {
                name = function.path("name").asText();
            }
            arguments.append(function.path("arguments").asText(""));
        }

        ToolCall toToolCall() {
            return ToolCall.builder()
                    .id(id)
                    .name(name)
                    .arguments(parseArguments(arguments.isEmpty() ? "{}" : arguments.toString()))
                    .build();
        }
    }

    private List<ToolCall> parseToolCalls(JsonNode toolCallsNode) {
        List<ToolCall> toolCalls = new ArrayList<>();
        for (JsonNode toolCallNode : toolCallsNode) {
            JsonNode function = toolCallNode.path("function");
            toolCalls.add(ToolCall.builder()
                    .id(toolCallNode.path("id").asText(null))
                    .name(function.path("name").asText(null))
                    .arguments(parseArguments(function.path("arguments").asText("{}")))
                    .build());
        }
        return toolCalls;
    }

    private Map<String, Object> parseArguments(String argumentsJson) {
        try {
            return MAPPER.readValue(argumentsJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (JacksonException e) {
            return Map.of();
        }
    }

    private JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JacksonException e) {
            throw new IllegalStateException("Provider returned malformed JSON: " + e.getMessage(), e);
        }
    }
}
