package io.github.manishpateluk.llmrouter.provider.perplexity;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import io.github.manishpateluk.llmrouter.model.Message;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.model.Role;
import io.github.manishpateluk.llmrouter.model.ToolCall;
import io.github.manishpateluk.llmrouter.model.ToolDefinition;
import io.github.manishpateluk.llmrouter.model.Usage;
import io.github.manishpateluk.llmrouter.provider.Provider;
import io.github.manishpateluk.llmrouter.provider.ProviderAdapter;
import io.github.manishpateluk.llmrouter.provider.compatible.HttpTransport;
import io.github.manishpateluk.llmrouter.provider.compatible.HttpTransport.HttpRequestRecord;
import io.github.manishpateluk.llmrouter.provider.compatible.HttpTransport.HttpResponseRecord;
import io.github.manishpateluk.llmrouter.provider.compatible.JdkHttpTransport;
import io.github.manishpateluk.llmrouter.provider.compatible.ProviderHttpException;

/**
 * {@link ProviderAdapter} for Perplexity's Agent API ({@code POST /v1/agent}) — no official Java
 * SDK exists, so it speaks raw HTTP through the same {@link HttpTransport} seam as the
 * OpenAI-compatible adapters.
 *
 * <p>Wire mapping:
 * <ul>
 *   <li>Model ids are the Agent API's own ({@code perplexity/sonar}, {@code openai/gpt-6-luna},
 *       ...). A model string {@code preset:<name>} is sent as {@code preset: <name>} instead, so a
 *       route can name one of Perplexity's presets ({@code fast}, {@code low}, ...).</li>
 *   <li>Every model request switches on Perplexity's {@code web_search} tool, so answers stay
 *       grounded in the web as Sonar's were; presets bring their own tools and are left as
 *       configured. The search results become {@code Response.citations}, ordered by their
 *       {@code id} (the number the answer's {@code [n]} markers use).</li>
 *   <li>System instructions and {@code SYSTEM} history turns go in {@code instructions}; the rest
 *       of the history and the prompt go in {@code input} as {@code message},
 *       {@code function_call} and {@code function_call_output} items. A {@code TOOL} turn
 *       without a {@code toolCallId} is sent as a plain user message.</li>
 *   <li>Images go as {@code input_image} parts and every other attachment as an
 *       {@code input_file} part, both as base64 data URIs.</li>
 *   <li>Reasoning effort goes as {@code reasoning: {effort}}; the Agent API has no thinking
 *       budget, so a budget is not sent.</li>
 *   <li>{@code usage.cost.total_cost}, which includes per-search tool fees, is reported as the
 *       response's estimated cost, and the router keeps it rather than re-pricing from the
 *       capability table.</li>
 * </ul>
 *
 * <p>Not supported: replaying a {@code function_call}'s {@code thought_signature} (the unified
 * {@code ToolCall} has nowhere to keep it), {@code previous_response_id} continuation, and
 * background runs. Attachments are re-embedded inline on every call.
 */
public final class PerplexityAdapter implements ProviderAdapter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String AGENT_URL = "https://api.perplexity.ai/v1/agent";
    private static final String PRESET_PREFIX = "preset:";
    private static final long DEFAULT_MAX_OUTPUT_TOKENS = 4096L;

    private final String apiKey;
    private final HttpTransport transport;

    public PerplexityAdapter(String apiKey) {
        this(apiKey, new JdkHttpTransport());
    }

    public PerplexityAdapter(String apiKey, HttpTransport transport) {
        this.apiKey = (apiKey == null || apiKey.isBlank()) ? null : apiKey;
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
    }

    @Override
    public Provider id() {
        return Provider.PERPLEXITY;
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null;
    }

    @Override
    public Response send(String model, Request adaptedRequest) {
        requireAvailable();
        return parseResponse(transport.send(toHttpRequest(buildBody(model, adaptedRequest))));
    }

    @Override
    public CompletableFuture<Response> sendAsync(String model, Request adaptedRequest) {
        requireAvailable();
        return transport.sendAsync(toHttpRequest(buildBody(model, adaptedRequest))).thenApply(this::parseResponse);
    }

    /**
     * Streams the run as server-sent events: each {@code response.output_text.delta} goes to
     * {@code onText} as it arrives, and the {@code response.completed} event supplies the tool
     * calls, citations and usage. {@code original} holds every event, in order.
     */
    @Override
    public Response sendStreaming(String model, Request adaptedRequest, Consumer<String> onText) {
        requireAvailable();
        ObjectNode body = buildBody(model, adaptedRequest);
        body.put("stream", true);
        StreamAccumulator accumulator = new StreamAccumulator(onText);
        HttpResponseRecord status = transport.sendStreaming(toHttpRequest(body), accumulator::acceptLine);
        if (status.statusCode() >= 300) {
            throw new ProviderHttpException(status.statusCode(), status.body());
        }
        return accumulator.toResponse();
    }

    private void requireAvailable() {
        if (apiKey == null) {
            throw new IllegalStateException(getClass().getSimpleName() + " has no credentials configured");
        }
    }

    private HttpRequestRecord toHttpRequest(ObjectNode body) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        headers.put("Content-Type", "application/json");
        return new HttpRequestRecord(AGENT_URL, headers, body.toString());
    }

    // ---- request ----

    private ObjectNode buildBody(String model, Request request) {
        ObjectNode body = MAPPER.createObjectNode();
        boolean preset = model.startsWith(PRESET_PREFIX);
        if (preset) {
            body.put("preset", model.substring(PRESET_PREFIX.length()));
        } else {
            body.put("model", model);
        }

        String instructions = buildInstructions(request);
        if (instructions != null) {
            body.put("instructions", instructions);
        }
        body.set("input", buildInput(request));

        Long maxOutputTokens = resolveMaxOutputTokens(model, preset, request);
        if (maxOutputTokens != null) {
            body.put("max_output_tokens", maxOutputTokens);
        }
        if (request.getTemperature() != null) {
            body.put("temperature", request.getTemperature());
        }
        if (request.getTopP() != null) {
            body.put("top_p", request.getTopP());
        }
        if (request.getReasoningEffort() != null) {
            body.putObject("reasoning").put("effort", request.getReasoningEffort());
        }

        ArrayNode tools = MAPPER.createArrayNode();
        if (!preset) {
            tools.addObject().put("type", "web_search");
        }
        for (ToolDefinition tool : request.getTools()) {
            ObjectNode function = tools.addObject();
            function.put("type", "function");
            function.put("name", tool.getName());
            function.put("description", tool.getDescription());
            function.set("parameters", MAPPER.valueToTree(tool.getParameters()));
        }
        if (!tools.isEmpty()) {
            body.set("tools", tools);
        }

        if (request.getResponseSchema() != null) {
            ObjectNode format = body.putObject("response_format");
            format.put("type", "json_schema");
            ObjectNode jsonSchema = format.putObject("json_schema");
            jsonSchema.put("name", "response");
            jsonSchema.set("schema", MAPPER.valueToTree(request.getResponseSchema()));
        }
        return body;
    }

    /**
     * The model's maximum output from the capability table (or a 4096 default for a model the
     * table doesn't list), capped at the request's {@code maxOutputTokens}. Always sent for a
     * model, since {@code anthropic/*} models reject a request without it. A preset keeps its own
     * limit unless the request sets one.
     */
    private Long resolveMaxOutputTokens(String model, boolean preset, Request request) {
        if (preset) {
            return request.getMaxOutputTokens() == null ? null : request.getMaxOutputTokens().longValue();
        }
        long modelMax = ModelCapabilityTable.findModel(id(), model)
                .map(ModelEntry::getMaxOutputTokens)
                .map(Integer::longValue)
                .orElse(DEFAULT_MAX_OUTPUT_TOKENS);
        return request.getMaxOutputTokens() == null ? modelMax : Math.min(modelMax, request.getMaxOutputTokens());
    }

    private static String buildInstructions(Request request) {
        StringBuilder combined = new StringBuilder();
        if (request.getSystemInstructions() != null && !request.getSystemInstructions().isBlank()) {
            combined.append(request.getSystemInstructions());
        }
        for (Message message : request.getHistory()) {
            if (message.getRole() == Role.SYSTEM) {
                if (combined.length() > 0) {
                    combined.append("\n\n");
                }
                combined.append(message.getContent());
            }
        }
        return combined.length() == 0 ? null : combined.toString();
    }

    private ArrayNode buildInput(Request request) {
        ArrayNode input = MAPPER.createArrayNode();
        for (Message message : request.getHistory()) {
            switch (message.getRole()) {
                case USER -> addMessage(input, "user", message.getContent());
                case ASSISTANT -> {
                    if (message.getContent() != null && !message.getContent().isEmpty()) {
                        addMessage(input, "assistant", message.getContent());
                    }
                    for (ToolCall call : message.getToolCalls()) {
                        ObjectNode item = input.addObject();
                        item.put("type", "function_call");
                        item.put("call_id", call.getId());
                        item.put("name", call.getName());
                        item.put("arguments", MAPPER.valueToTree(call.getArguments()).toString());
                    }
                }
                case SYSTEM -> { /* folded into instructions */ }
                case TOOL -> {
                    if (message.getToolCallId() != null) {
                        ObjectNode item = input.addObject();
                        item.put("type", "function_call_output");
                        item.put("call_id", message.getToolCallId());
                        item.put("output", message.getContent());
                    } else {
                        addMessage(input, "user", "Tool result: " + message.getContent());
                    }
                }
            }
        }

        if (request.getAttachments().isEmpty()) {
            addMessage(input, "user", request.getPrompt());
        } else {
            ObjectNode message = input.addObject();
            message.put("type", "message");
            message.put("role", "user");
            ArrayNode parts = message.putArray("content");
            parts.addObject().put("type", "input_text").put("text", request.getPrompt());
            for (Attachment attachment : request.getAttachments()) {
                parts.add(attachmentPart(attachment));
            }
        }
        return input;
    }

    private static void addMessage(ArrayNode input, String role, String content) {
        ObjectNode item = input.addObject();
        item.put("type", "message");
        item.put("role", role);
        item.put("content", content == null ? "" : content);
    }

    /**
     * {@code input_image} for images, {@code input_file} for everything else, both as data URIs.
     * A file without a filename is left for Perplexity to name from its detected type.
     */
    private static ObjectNode attachmentPart(Attachment attachment) {
        String dataUri = "data:" + attachment.getMediaType() + ";base64,"
                + Base64.getEncoder().encodeToString(attachment.getData());
        ObjectNode part = MAPPER.createObjectNode();
        if (attachment.getMediaType() != null && attachment.getMediaType().startsWith("image/")) {
            part.put("type", "input_image");
            part.put("image_url", dataUri);
        } else {
            part.put("type", "input_file");
            part.put("file_data", dataUri);
            if (attachment.getFilename() != null) {
                part.put("filename", attachment.getFilename());
            }
        }
        return part;
    }

    // ---- response ----

    private Response parseResponse(HttpResponseRecord httpResponse) {
        if (httpResponse.statusCode() >= 300) {
            throw new ProviderHttpException(httpResponse.statusCode(), httpResponse.body());
        }
        JsonNode root = readTree(httpResponse.body());
        requireNotFailed(root);
        return fromResponseObject(root, collectText(root.path("output")), root);
    }

    /** A run that ended {@code failed} or {@code cancelled} fails the attempt, like an HTTP error. */
    private static void requireNotFailed(JsonNode response) {
        String status = response.path("status").asText("");
        if (status.equals("failed") || status.equals("cancelled")) {
            throw new IllegalStateException("Perplexity reported a " + status + " response: " + response.path("error"));
        }
    }

    private Response fromResponseObject(JsonNode response, String content, JsonNode original) {
        JsonNode output = response.path("output");
        return Response.builder()
                .content(content)
                .toolCalls(parseToolCalls(output))
                .usage(parseUsage(response.path("usage")))
                .citations(parseCitations(output))
                .original(original)
                .build();
    }

    private static String collectText(JsonNode output) {
        StringBuilder text = new StringBuilder();
        for (JsonNode item : output) {
            if (item.path("type").asText("").equals("message")) {
                for (JsonNode part : item.path("content")) {
                    if (part.path("type").asText("").equals("output_text")) {
                        text.append(part.path("text").asText(""));
                    }
                }
            }
        }
        return text.toString();
    }

    private List<ToolCall> parseToolCalls(JsonNode output) {
        List<ToolCall> toolCalls = new ArrayList<>();
        for (JsonNode item : output) {
            if (item.path("type").asText("").equals("function_call")) {
                toolCalls.add(ToolCall.builder()
                        .id(item.path("call_id").asText(null))
                        .name(item.path("name").asText(null))
                        .arguments(parseArguments(item.path("arguments").asText("{}")))
                        .build());
            }
        }
        return toolCalls;
    }

    /**
     * Token counts, plus {@code cost.total_cost} as the estimated cost when Perplexity reports
     * one (it covers search fees the capability table's token prices don't).
     */
    private static Usage parseUsage(JsonNode usage) {
        double totalCostUsd = usage.path("cost").path("total_cost").asDouble(0);
        return Usage.builder()
                .inputTokens(usage.path("input_tokens").asInt(0))
                .outputTokens(usage.path("output_tokens").asInt(0))
                .reasoningTokens(usage.path("output_tokens_details").path("reasoning_tokens").asInt(0))
                .estimatedCostUsdCents((int) Math.round(totalCostUsd * 100.0))
                .estimatedCostUsdMicros(Math.round(totalCostUsd * 1_000_000.0))
                .build();
    }

    /**
     * The web pages the run's {@code search_results} items returned, ordered by their {@code id}
     * — the number an answer's {@code [n]} (or {@code [web:n]}) marker refers to — then any
     * further URLs cited only through a message's {@code url_citation} annotations. Each URL
     * appears once.
     */
    private static List<Citation> parseCitations(JsonNode output) {
        List<JsonNode> results = new ArrayList<>();
        for (JsonNode item : output) {
            if (item.path("type").asText("").equals("search_results")) {
                item.path("results").forEach(results::add);
            }
        }
        results.sort(Comparator.comparingLong(result -> result.path("id").asLong(Long.MAX_VALUE)));

        Map<String, Citation> byUrl = new LinkedHashMap<>();
        for (JsonNode result : results) {
            String url = result.path("url").asText("");
            if (!url.isEmpty()) {
                byUrl.putIfAbsent(url, Citation.builder()
                        .url(url)
                        .title(nonBlank(result.path("title")))
                        .snippet(nonBlank(result.path("snippet")))
                        .build());
            }
        }
        for (JsonNode item : output) {
            for (JsonNode part : item.path("content")) {
                for (JsonNode annotation : part.path("annotations")) {
                    String url = annotation.path("url").asText("");
                    if (annotation.path("type").asText("").equals("url_citation") && !url.isEmpty()) {
                        byUrl.putIfAbsent(url, Citation.builder().url(url).title(nonBlank(annotation.path("title"))).build());
                    }
                }
            }
        }
        return List.copyOf(byUrl.values());
    }

    private static String nonBlank(JsonNode text) {
        String value = text.asText("");
        return value.isBlank() ? null : value;
    }

    /**
     * Folds the Agent API's SSE stream back into one response. {@code data:} lines carry typed
     * JSON events; {@code event:} lines, blank separators, {@code :} keep-alives and a closing
     * {@code [DONE]} are ignored. Output items are also collected from
     * {@code response.output_item.done}, in case the stream ends without
     * {@code response.completed}.
     */
    private final class StreamAccumulator {

        private final Consumer<String> onText;
        private final StringBuilder content = new StringBuilder();
        private final ArrayNode events = MAPPER.createArrayNode();
        private final ArrayNode doneItems = MAPPER.createArrayNode();
        private JsonNode completed;

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
            JsonNode event = readTree(data);
            events.add(event);
            switch (event.path("type").asText("")) {
                case "response.output_text.delta" -> {
                    String text = event.path("delta").asText("");
                    if (!text.isEmpty()) {
                        content.append(text);
                        onText.accept(text);
                    }
                }
                case "response.output_item.done" -> doneItems.add(event.path("item"));
                case "response.completed" -> completed = event.path("response");
                case "response.failed", "error" -> throw new IllegalStateException(
                        "Perplexity reported an error mid-stream: " + event.path("error"));
                default -> { /* lifecycle and reasoning/search progress events */ }
            }
        }

        Response toResponse() {
            ObjectNode response = completed != null && completed.isObject()
                    ? (ObjectNode) completed.deepCopy()
                    : MAPPER.createObjectNode();
            requireNotFailed(response);
            if (!response.path("output").isArray() || response.path("output").isEmpty()) {
                response.set("output", doneItems);
            }
            String text = content.isEmpty() ? collectText(response.path("output")) : content.toString();
            return fromResponseObject(response, text, events);
        }
    }

    private static Map<String, Object> parseArguments(String argumentsJson) {
        try {
            return MAPPER.readValue(argumentsJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (JacksonException e) {
            return Map.of();
        }
    }

    private static JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JacksonException e) {
            throw new IllegalStateException("Perplexity returned malformed JSON: " + e.getMessage(), e);
        }
    }
}
