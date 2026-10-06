package io.github.manishpateluk.llmrouter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.github.manishpateluk.llmrouter.capability.ModelCapabilityTable;
import io.github.manishpateluk.llmrouter.capability.ModelEntry;
import io.github.manishpateluk.llmrouter.config.Feature;
import io.github.manishpateluk.llmrouter.config.RouteEntry;
import io.github.manishpateluk.llmrouter.config.RouterConfig;
import io.github.manishpateluk.llmrouter.error.InvalidConfigException;
import io.github.manishpateluk.llmrouter.error.InvalidRequestException;
import io.github.manishpateluk.llmrouter.error.NoProvidersConfiguredException;
import io.github.manishpateluk.llmrouter.error.RouterExhaustedException;
import io.github.manishpateluk.llmrouter.model.AttemptOutcome;
import io.github.manishpateluk.llmrouter.model.AttemptRecord;
import io.github.manishpateluk.llmrouter.model.EmbeddingRequest;
import io.github.manishpateluk.llmrouter.model.EmbeddingResponse;
import io.github.manishpateluk.llmrouter.model.Message;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.model.ToolDefinitionValidator;
import io.github.manishpateluk.llmrouter.model.Usage;
import io.github.manishpateluk.llmrouter.negotiation.CapabilityNegotiator;
import io.github.manishpateluk.llmrouter.negotiation.NegotiationResult;
import io.github.manishpateluk.llmrouter.provider.CredentialResolver;
import io.github.manishpateluk.llmrouter.provider.Provider;
import io.github.manishpateluk.llmrouter.provider.ProviderAdapter;
import io.github.manishpateluk.llmrouter.provider.anthropic.AnthropicAdapter;
import io.github.manishpateluk.llmrouter.provider.compatible.HuggingFaceAdapter;
import io.github.manishpateluk.llmrouter.provider.compatible.NvidiaAdapter;
import io.github.manishpateluk.llmrouter.provider.compatible.OpenRouterAdapter;
import io.github.manishpateluk.llmrouter.provider.compatible.PerplexityAdapter;
import io.github.manishpateluk.llmrouter.provider.openai.OpenAiAdapter;
import io.github.manishpateluk.llmrouter.routing.RouteResolver;
import io.github.manishpateluk.llmrouter.routing.TokenEstimator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The router's single entry point — see {@code LIBRARY_SPEC.md} §1-§2. Construct one and reuse
 * it: credential detection happens once, when this instance is built, and the instance is safe
 * to share across threads (e.g. as a singleton bean in a Spring application) — every method
 * here is stateless over the request it's given.
 *
 * <p>Every call variant funnels into {@link #complete(Request)} / {@link #completeAsync(Request)}
 * — the cascading overloads below just build a {@link Request} and delegate. Use
 * {@code Request.builder()} directly for the fully general case (combining {@code tools},
 * {@code responseSchema}, and {@code attachments} in one call).
 *
 * <p>An optional {@link RequestInterceptor} can be supplied at construction — see its javadoc
 * and {@code LIBRARY_SPEC.md} §4.1 — as a last-chance hook to inspect or modify the fully
 * negotiated request immediately before it's sent for each attempt.
 */
public final class LlmRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);
    private static final ObjectMapper STRUCTURED_OUTPUT_MAPPER = new ObjectMapper();
    private static final RequestInterceptor NO_OP_INTERCEPTOR = (provider, model, request) -> request;

    private final Map<Provider, ProviderAdapter> adapters;
    private final RequestInterceptor requestInterceptor;

    /** Detects credentials from the environment (§6) and builds all six built-in provider adapters. */
    public LlmRouter() {
        this(defaultAdapters(new CredentialResolver()), NO_OP_INTERCEPTOR);
    }

    /**
     * Detects credentials from the environment (§6) and builds all six built-in provider
     * adapters, with {@code requestInterceptor} as the last-chance hook before each attempt is
     * sent — see {@link RequestInterceptor} and {@code LIBRARY_SPEC.md} §4.1.
     */
    public LlmRouter(RequestInterceptor requestInterceptor) {
        this(defaultAdapters(new CredentialResolver()), requestInterceptor);
    }

    /**
     * Builds a router from an explicit set of adapters — bypasses environment-based credential
     * detection entirely. Useful for supplying a custom-configured provider client (e.g. one
     * pointed at Bedrock/Vertex), for restricting the router to a subset of providers, or for
     * tests.
     */
    public LlmRouter(List<ProviderAdapter> adapters) {
        this(adapters, NO_OP_INTERCEPTOR);
    }

    /**
     * Builds a router from an explicit set of adapters, with {@code requestInterceptor} as the
     * last-chance hook before each attempt is sent — see {@link RequestInterceptor} and
     * {@code LIBRARY_SPEC.md} §4.1.
     */
    public LlmRouter(List<ProviderAdapter> adapters, RequestInterceptor requestInterceptor) {
        Objects.requireNonNull(adapters, "adapters must not be null");
        Objects.requireNonNull(requestInterceptor, "requestInterceptor must not be null");
        Map<Provider, ProviderAdapter> byProvider = new LinkedHashMap<>();
        for (ProviderAdapter adapter : adapters) {
            byProvider.put(adapter.id(), adapter);
        }
        this.adapters = Map.copyOf(byProvider);
        this.requestInterceptor = requestInterceptor;
    }

    private static List<ProviderAdapter> defaultAdapters(CredentialResolver credentials) {
        return List.of(
                new AnthropicAdapter(credentials.resolve(Provider.ANTHROPIC).orElse(null)),
                new OpenAiAdapter(credentials.resolve(Provider.OPENAI).orElse(null)),
                new PerplexityAdapter(credentials.resolve(Provider.PERPLEXITY).orElse(null)),
                new NvidiaAdapter(credentials.resolve(Provider.NVIDIA).orElse(null)),
                new HuggingFaceAdapter(credentials.resolve(Provider.HUGGINGFACE).orElse(null)),
                new OpenRouterAdapter(credentials.resolve(Provider.OPENROUTER).orElse(null)));
    }

    // ---- Sync cascading overloads (§3) ----

    public Response complete(String prompt) {
        return complete(Request.builder().prompt(prompt).build());
    }

    public Response complete(String prompt, RouterConfig config) {
        return complete(Request.builder().prompt(prompt).config(config).build());
    }

    public Response complete(String prompt, List<Message> history) {
        return complete(Request.builder().prompt(prompt).history(history).build());
    }

    public Response complete(String prompt, List<Message> history, RouterConfig config) {
        return complete(Request.builder().prompt(prompt).history(history).config(config).build());
    }

    public Response complete(String prompt, String systemInstructions) {
        return complete(Request.builder().prompt(prompt).systemInstructions(systemInstructions).build());
    }

    public Response complete(String prompt, String systemInstructions, RouterConfig config) {
        return complete(Request.builder().prompt(prompt).systemInstructions(systemInstructions).config(config).build());
    }

    /** The canonical, most general sync call — every other {@code complete} overload delegates here. */
    public Response complete(Request request) {
        return completeVia(request, ProviderAdapter::send);
    }

    /**
     * {@link #complete(Request)}, streaming the response's text to {@code listener} as it's
     * generated — for showing an answer to a user as it's written. Everything else is identical:
     * routing, capability negotiation, the request interceptor, fallback, and the returned
     * {@link Response} (whose content is the full text). Providers whose adapter has no native
     * streaming deliver their text in one piece. If an attempt fails after streaming some text,
     * {@link StreamListener#onReset()} is called before falling back to the next candidate.
     *
     * <p>An exception thrown by {@code listener} itself is not a provider failure: it ends the call
     * immediately and is rethrown unchanged, with no reset and no fallback, so a broken listener
     * never costs a request per remaining candidate.
     */
    public Response completeStreaming(Request request, StreamListener listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        try {
            return completeVia(request, (adapter, model, toSend) -> {
                StreamingAttempt attempt = new StreamingAttempt(listener);
                try {
                    return adapter.sendStreaming(model, toSend, attempt::deliver);
                } catch (RuntimeException e) {
                    attempt.failed(e);
                    throw e;
                }
            });
        } catch (ListenerFailure e) {
            throw e.listenerException();
        }
    }

    /**
     * Async counterpart of {@link #completeStreaming}, with the same contract: text streams to
     * {@code listener} as it's generated, {@link StreamListener#onReset()} precedes a fallback
     * after a mid-stream failure, and an exception from {@code listener} itself fails the returned
     * future with that exception, unchanged and without fallback. The listener is called from a
     * background thread, one call at a time and in order.
     *
     * <p>Like {@link #completeAsync(Request)}, invalid requests fail the returned future rather
     * than throwing.
     */
    public CompletableFuture<Response> completeStreamingAsync(Request request, StreamListener listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        return completeAsyncVia(request, (adapter, model, toSend) -> {
            StreamingAttempt attempt = new StreamingAttempt(listener);
            CompletableFuture<Response> sent;
            try {
                sent = adapter.sendStreamingAsync(model, toSend, attempt::deliver);
            } catch (RuntimeException e) {
                sent = CompletableFuture.failedFuture(e);
            }
            return sent.exceptionally(throwable -> {
                Throwable cause = unwrap(throwable);
                attempt.failed(cause);
                throw new CompletionException(cause);
            });
        });
    }

    /**
     * One attempt's view of a {@link StreamListener}: forwards non-empty text, remembers whether
     * any was delivered, and turns anything the listener throws into a {@link ListenerFailure}.
     */
    private static final class StreamingAttempt {

        private final StreamListener listener;
        private volatile boolean streamed;

        StreamingAttempt(StreamListener listener) {
            this.listener = listener;
        }

        void deliver(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            streamed = true;
            try {
                listener.onText(delta);
            } catch (RuntimeException e) {
                throw new ListenerFailure(e);
            }
        }

        /**
         * Called when the attempt failed with {@code e}. Throws the {@link ListenerFailure} if the
         * listener caused it (unwrapped from anything the adapter or SDK added); otherwise resets
         * the listener if it had received text, so the caller can go on to the next candidate.
         */
        void failed(Throwable e) {
            ListenerFailure listenerFailure = ListenerFailure.findIn(e);
            if (listenerFailure != null) {
                throw listenerFailure;
            }
            if (streamed) {
                try {
                    listener.onReset();
                } catch (RuntimeException resetFailure) {
                    throw new ListenerFailure(resetFailure);
                }
            }
        }
    }

    /**
     * Carries an exception thrown by a {@link StreamListener} out through the adapter and the
     * fallback loop, which must not mistake it for a failed attempt.
     */
    private static final class ListenerFailure extends RuntimeException {

        ListenerFailure(RuntimeException listenerException) {
            super(listenerException);
        }

        RuntimeException listenerException() {
            return (RuntimeException) getCause();
        }

        static ListenerFailure findIn(Throwable e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof ListenerFailure failure) {
                    return failure;
                }
            }
            return null;
        }
    }

    /**
     * Turns texts into embedding vectors — see {@link EmbeddingRequest} and {@code LIBRARY_SPEC.md}
     * §3.2. Tries {@code request.route} in order (falling back only across the candidates listed
     * there, since different models' vectors aren't comparable), or with no route, the first
     * available provider's default embedding model alone.
     *
     * @throws RouterExhaustedException if no candidate could embed the texts
     */
    public EmbeddingResponse embed(EmbeddingRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.getTexts() == null || request.getTexts().isEmpty()) {
            throw new IllegalArgumentException("EmbeddingRequest.texts must not be empty");
        }
        List<RouteEntry> candidates = request.getRoute();
        if (candidates == null || candidates.isEmpty()) {
            candidates = availableProviders().stream()
                    .filter(provider -> adapters.get(provider).defaultEmbeddingModel() != null)
                    .findFirst()
                    .map(provider -> List.of(RouteEntry.of(provider)))
                    .orElseThrow(() -> new NoProvidersConfiguredException(
                            "No configured provider offers embeddings (e.g. set OPENAI_API_KEY)"));
        }
        List<AttemptRecord> attempts = new ArrayList<>();
        for (RouteEntry candidate : candidates) {
            ProviderAdapter adapter = adapters.get(candidate.getProvider());
            if (adapter == null || !adapter.isAvailable()) {
                attempts.add(recordSkip(candidate, "no credentials"));
                continue;
            }
            String model = candidate.getModel() != null ? candidate.getModel() : adapter.defaultEmbeddingModel();
            if (model == null) {
                attempts.add(recordSkip(candidate, "provider offers no embeddings"));
                continue;
            }
            try {
                EmbeddingResponse fragment = adapter.embed(model, request.getTexts(), request.getDimensions());
                if (fragment.getVectors() == null || fragment.getVectors().size() != request.getTexts().size()) {
                    throw new IllegalStateException("expected " + request.getTexts().size() + " vectors, got "
                            + (fragment.getVectors() == null ? 0 : fragment.getVectors().size()));
                }
                return fragment.toBuilder()
                        .providerUsed(candidate.getProvider())
                        .modelUsed(model)
                        .usage(priceEmbedding(candidate.getProvider(), model, fragment.getUsage()))
                        .attempts(List.copyOf(attempts))
                        .build();
            } catch (RuntimeException e) {
                attempts.add(recordFailure(RouteEntry.of(candidate.getProvider(), model), e));
            }
        }
        throw new RouterExhaustedException(attempts);
    }

    /**
     * Fills in an embedding call's cost from the table's embedding prices (§7.3). A model with no
     * row there keeps a cost of 0, as an unknown chat model does; its token count is still reported.
     */
    private static Usage priceEmbedding(Provider provider, String model, Usage usage) {
        if (usage == null) {
            return null;
        }
        return ModelCapabilityTable.findEmbeddingModel(provider, model)
                .map(entry -> withEstimatedCost(usage, (usage.getInputTokens() / 1_000_000.0) * entry.getInputCostPerMillionTokens()))
                .orElse(usage);
    }

    /** How one attempt is sent: plain or streaming. */
    @FunctionalInterface
    private interface Sender {
        Response send(ProviderAdapter adapter, String model, Request toSend);
    }

    /** The shared sync fallback loop behind {@link #complete(Request)} and {@link #completeStreaming}. */
    private Response completeVia(Request request, Sender sender) {
        validate(request);
        RouterConfig config = resolveConfig(request);
        List<RouteEntry> candidates = resolveCandidates(request, config);

        List<AttemptRecord> attempts = new ArrayList<>();
        for (RouteEntry candidate : candidates) {
            ProviderAdapter adapter = adapters.get(candidate.getProvider());
            ModelEntry modelEntry = findModel(candidate);
            String skipReason = skipReason(adapter, candidate, modelEntry, request, config);
            if (skipReason != null) {
                attempts.add(recordSkip(candidate, skipReason));
                continue;
            }

            NegotiationResult negotiation = negotiate(modelEntry, request, config);

            try {
                Request toSend = requestInterceptor.beforeSend(candidate.getProvider(), candidate.getModel(), negotiation.getAdaptedRequest());
                Response fragment = sender.send(adapter, candidate.getModel(), toSend);
                return finalizeResponse(request, fragment, candidate, modelEntry, negotiation, attempts);
            } catch (ListenerFailure e) {
                throw e; // the caller's listener broke, not the provider — never fall back on it
            } catch (RuntimeException e) {
                attempts.add(recordFailure(candidate, e));
            }
        }

        log.error("Router exhausted after {} attempt(s)", attempts.size());
        throw new RouterExhaustedException(attempts);
    }

    // ---- Async cascading overloads (§11: async is a per-language idiom; CompletableFuture here) ----

    public CompletableFuture<Response> completeAsync(String prompt) {
        return completeAsync(Request.builder().prompt(prompt).build());
    }

    public CompletableFuture<Response> completeAsync(String prompt, RouterConfig config) {
        return completeAsync(Request.builder().prompt(prompt).config(config).build());
    }

    public CompletableFuture<Response> completeAsync(String prompt, List<Message> history) {
        return completeAsync(Request.builder().prompt(prompt).history(history).build());
    }

    public CompletableFuture<Response> completeAsync(String prompt, List<Message> history, RouterConfig config) {
        return completeAsync(Request.builder().prompt(prompt).history(history).config(config).build());
    }

    public CompletableFuture<Response> completeAsync(String prompt, String systemInstructions) {
        return completeAsync(Request.builder().prompt(prompt).systemInstructions(systemInstructions).build());
    }

    public CompletableFuture<Response> completeAsync(String prompt, String systemInstructions, RouterConfig config) {
        return completeAsync(Request.builder().prompt(prompt).systemInstructions(systemInstructions).config(config).build());
    }

    /** The canonical, most general async call — every other {@code completeAsync} overload delegates here. */
    public CompletableFuture<Response> completeAsync(Request request) {
        return completeAsyncVia(request, ProviderAdapter::sendAsync);
    }

    /** How one async attempt is sent: plain or streaming. */
    @FunctionalInterface
    private interface AsyncSender {
        CompletableFuture<Response> send(ProviderAdapter adapter, String model, Request toSend);
    }

    /** The shared async fallback loop behind {@link #completeAsync(Request)} and {@link #completeStreamingAsync}. */
    private CompletableFuture<Response> completeAsyncVia(Request request, AsyncSender sender) {
        try {
            validate(request);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        RouterConfig config = resolveConfig(request);
        List<RouteEntry> candidates = resolveCandidates(request, config);
        return attemptAsync(request, candidates, 0, new ArrayList<>(), sender);
    }

    /**
     * Callback-style async call: a thin wrapper over {@link #completeAsync(Request)} for callers
     * who'd rather not work with {@link CompletableFuture} directly.
     */
    public void completeAsync(Request request, Consumer<Response> onSuccess, Consumer<Throwable> onFailure) {
        completeAsync(request).whenComplete((response, throwable) -> {
            if (throwable != null) {
                onFailure.accept(unwrap(throwable));
            } else {
                onSuccess.accept(response);
            }
        });
    }

    private CompletableFuture<Response> attemptAsync(
            Request request, List<RouteEntry> candidates, int index, List<AttemptRecord> attempts, AsyncSender sender) {
        if (index >= candidates.size()) {
            log.error("Router exhausted after {} attempt(s)", attempts.size());
            return CompletableFuture.failedFuture(new RouterExhaustedException(attempts));
        }

        RouteEntry candidate = candidates.get(index);
        ProviderAdapter adapter = adapters.get(candidate.getProvider());
        ModelEntry modelEntry = findModel(candidate);
        RouterConfig config = resolveConfig(request);
        String skipReason = skipReason(adapter, candidate, modelEntry, request, config);
        if (skipReason != null) {
            attempts.add(recordSkip(candidate, skipReason));
            return attemptAsync(request, candidates, index + 1, attempts, sender);
        }

        NegotiationResult negotiation = negotiate(modelEntry, request, config);

        Request toSend;
        try {
            toSend = requestInterceptor.beforeSend(candidate.getProvider(), candidate.getModel(), negotiation.getAdaptedRequest());
        } catch (RuntimeException e) {
            attempts.add(recordFailure(candidate, e));
            return attemptAsync(request, candidates, index + 1, attempts, sender);
        }

        return sender.send(adapter, candidate.getModel(), toSend)
                .handle((fragment, throwable) -> {
                    if (throwable == null) {
                        return finalizeResponse(request, fragment, candidate, modelEntry, negotiation, attempts);
                    }
                    Throwable cause = unwrap(throwable);
                    if (cause instanceof ListenerFailure listenerFailure) {
                        // the caller's listener broke, not the provider — never fall back on it
                        throw new CompletionException(listenerFailure.listenerException());
                    }
                    attempts.add(recordFailure(candidate, cause));
                    return null;
                })
                .thenCompose(result -> result != null
                        ? CompletableFuture.completedFuture(result)
                        : attemptAsync(request, candidates, index + 1, attempts, sender));
    }

    private List<RouteEntry> resolveCandidates(Request request, RouterConfig config) {
        int promptTokens = TokenEstimator.estimateTokens(request.getPrompt());
        List<RouteEntry> candidates = RouteResolver.resolve(config, promptTokens, availableProviders(),
                model -> unmetRequiredFeatures(model, request, config).isEmpty());
        if (candidates.isEmpty() && config.getRoute() == null) {
            throw new NoProvidersConfiguredException(
                    "No provider credentials were detected. Set at least one provider's API key "
                            + "(see LIBRARY_SPEC.md §6.1) before calling the router.");
        }
        log.debug("Resolved {} candidate(s): {}", candidates.size(), candidates);
        return candidates;
    }

    private Set<Provider> availableProviders() {
        return Arrays.stream(Provider.values())
                .filter(provider -> adapters.containsKey(provider) && adapters.get(provider).isAvailable())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static ModelEntry findModel(RouteEntry candidate) {
        return candidate.isProviderOnly()
                ? null
                : ModelCapabilityTable.findModel(candidate.getProvider(), candidate.getModel()).orElse(null);
    }

    /**
     * Why {@code candidate} must be skipped without being attempted, or {@code null} if it should
     * be tried: no credentials (§5.1), or it can't honor one of {@code requiredFeatures} (§5.4).
     * A model missing from the capability table has no data to check against, so — consistent
     * with how negotiation treats it — it's tried optimistically rather than skipped.
     */
    private static String skipReason(
            ProviderAdapter adapter, RouteEntry candidate, ModelEntry modelEntry, Request request, RouterConfig config) {
        if (adapter == null || !adapter.isAvailable()) {
            return "no API key detected";
        }
        if (candidate.isProviderOnly()) {
            // RouteResolver leaves a provider-only entry unexpanded only when none of its models qualified.
            return "no " + candidate.getProvider() + " model supports required feature(s) "
                    + requiredFeaturesInUse(request, config);
        }
        if (modelEntry != null) {
            List<Feature> unmet = unmetRequiredFeatures(modelEntry, request, config);
            if (!unmet.isEmpty()) {
                return "model does not support required feature(s) " + unmet;
            }
        }
        return null;
    }

    private static List<Feature> unmetRequiredFeatures(ModelEntry modelEntry, Request request, RouterConfig config) {
        return CapabilityNegotiator.unmetRequiredFeatures(
                modelEntry, request, config.getStructuredOutputStrategy(), config.getRequiredFeatures());
    }

    /** The required features this particular request actually uses, for skip messages. */
    private static List<Feature> requiredFeaturesInUse(Request request, RouterConfig config) {
        return Arrays.stream(Feature.values())
                .filter(config.getRequiredFeatures()::contains)
                .filter(feature -> switch (feature) {
                    case TOOLS -> !request.getTools().isEmpty();
                    case RESPONSE_SCHEMA -> request.getResponseSchema() != null;
                    case ATTACHMENTS -> !request.getAttachments().isEmpty();
                })
                .toList();
    }

    private NegotiationResult negotiate(ModelEntry modelEntry, Request request, RouterConfig config) {
        if (modelEntry == null) {
            // Unknown model (not in the capability table) — nothing to negotiate against; send as-is,
            // optimistically forwarding temperature/topP since there's no capability data to check them against.
            Request adapted = request.toBuilder()
                    .temperature(config.getTemperature())
                    .topP(config.getTopP())
                    .build();
            return NegotiationResult.builder()
                    .adaptedRequest(adapted)
                    .droppedFeatures(List.of())
                    .structuredOutputViaPromptFallback(false)
                    .build();
        }
        NegotiationResult result = CapabilityNegotiator.negotiate(
                modelEntry, request, config.getStructuredOutputStrategy(), config.getTemperature(), config.getTopP());
        if (!result.getDroppedFeatures().isEmpty()) {
            log.warn("Dropped features {} for {}/{} due to capability mismatch",
                    result.getDroppedFeatures(), modelEntry.getProvider(), modelEntry.getModel());
        }
        return result;
    }

    private Response finalizeResponse(
            Request originalRequest,
            Response fragment,
            RouteEntry candidate,
            ModelEntry modelEntry,
            NegotiationResult negotiation,
            List<AttemptRecord> priorAttempts) {

        Usage usage = modelEntry == null
                ? fragment.getUsage()
                : withEstimatedCost(fragment.getUsage(), estimateCostUsd(modelEntry, fragment.getUsage()));

        Map<String, Object> structuredOutput = null;
        if (originalRequest.getResponseSchema() != null && !negotiation.getDroppedFeatures().contains("responseSchema")) {
            structuredOutput = tryParseStructuredOutput(fragment.getContent());
        }

        Response response = fragment.toBuilder()
                .providerUsed(candidate.getProvider())
                .modelUsed(candidate.getModel())
                .usage(usage)
                .structuredOutput(structuredOutput)
                .droppedFeatures(negotiation.getDroppedFeatures())
                .attempts(List.copyOf(priorAttempts))
                .build();

        log.info("Served by {}/{} ({} input, {} output tokens, {} cent(s) estimated)",
                candidate.getProvider(), candidate.getModel(), usage.getInputTokens(), usage.getOutputTokens(), usage.getEstimatedCostUsdCents());
        return response;
    }

    private static double estimateCostUsd(ModelEntry model, Usage usage) {
        double inputCost = (usage.getInputTokens() / 1_000_000.0) * model.getInputCostPerMillionTokens();
        double outputCost = (usage.getOutputTokens() / 1_000_000.0) * model.getOutputCostPerMillionTokens();
        return inputCost + outputCost;
    }

    /** Sets both cost fields from one dollar figure, so cents and micro-dollars never disagree. */
    private static Usage withEstimatedCost(Usage usage, double costUsd) {
        return usage.toBuilder()
                .estimatedCostUsdCents((int) Math.round(costUsd * 100.0))
                .estimatedCostUsdMicros(Math.round(costUsd * 1_000_000.0))
                .build();
    }

    private static Map<String, Object> tryParseStructuredOutput(String content) {
        try {
            return STRUCTURED_OUTPUT_MAPPER.readValue(content, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("Could not parse structured output from response content: {}", e.getMessage());
            return null;
        }
    }

    private static AttemptRecord recordSkip(RouteEntry candidate, String reason) {
        log.warn("Skipping {}/{}: {}", candidate.getProvider(), candidate.getModel(), reason);
        return AttemptRecord.builder()
                .provider(candidate.getProvider())
                .model(candidate.getModel())
                .outcome(AttemptOutcome.SKIPPED)
                .reason(reason)
                .build();
    }

    private static AttemptRecord recordFailure(RouteEntry candidate, Throwable e) {
        log.error("Attempt failed for {}/{}: {}", candidate.getProvider(), candidate.getModel(), e.getMessage());
        return AttemptRecord.builder()
                .provider(candidate.getProvider())
                .model(candidate.getModel())
                .outcome(AttemptOutcome.FAILED)
                .reason(e.getMessage())
                .build();
    }

    private static Throwable unwrap(Throwable e) {
        return e instanceof java.util.concurrent.CompletionException && e.getCause() != null ? e.getCause() : e;
    }

    private static RouterConfig resolveConfig(Request request) {
        RouterConfig config = request.getConfig();
        return config != null ? config : RouterConfig.builder().build();
    }

    private static void validate(Request request) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.getPrompt() == null || request.getPrompt().isBlank()) {
            throw new IllegalArgumentException("Request.prompt must not be null or blank");
        }
        RouterConfig config = resolveConfig(request);
        if (config.getThinkingLevel() == null) {
            throw new InvalidConfigException("RouterConfig.thinkingLevel must not be null");
        }
        if (config.getStructuredOutputStrategy() == null) {
            throw new InvalidConfigException("RouterConfig.structuredOutputStrategy must not be null");
        }
        if (config.getRequiredFeatures() == null) {
            throw new InvalidConfigException("RouterConfig.requiredFeatures must not be null");
        }
        List<String> toolProblems = ToolDefinitionValidator.validate(request.getTools());
        if (!toolProblems.isEmpty()) {
            throw new InvalidRequestException("Invalid tool definitions:\n  - " + String.join("\n  - ", toolProblems));
        }
    }
}
