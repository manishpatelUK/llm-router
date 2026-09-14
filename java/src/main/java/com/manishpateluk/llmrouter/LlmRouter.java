package com.manishpateluk.llmrouter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manishpateluk.llmrouter.capability.ModelCapabilityTable;
import com.manishpateluk.llmrouter.capability.ModelEntry;
import com.manishpateluk.llmrouter.config.RouteEntry;
import com.manishpateluk.llmrouter.config.RouterConfig;
import com.manishpateluk.llmrouter.error.InvalidConfigException;
import com.manishpateluk.llmrouter.error.NoProvidersConfiguredException;
import com.manishpateluk.llmrouter.error.RouterExhaustedException;
import com.manishpateluk.llmrouter.model.AttemptOutcome;
import com.manishpateluk.llmrouter.model.AttemptRecord;
import com.manishpateluk.llmrouter.model.Message;
import com.manishpateluk.llmrouter.model.Request;
import com.manishpateluk.llmrouter.model.Response;
import com.manishpateluk.llmrouter.model.Usage;
import com.manishpateluk.llmrouter.negotiation.CapabilityNegotiator;
import com.manishpateluk.llmrouter.negotiation.NegotiationResult;
import com.manishpateluk.llmrouter.provider.CredentialResolver;
import com.manishpateluk.llmrouter.provider.Provider;
import com.manishpateluk.llmrouter.provider.ProviderAdapter;
import com.manishpateluk.llmrouter.provider.anthropic.AnthropicAdapter;
import com.manishpateluk.llmrouter.provider.compatible.HuggingFaceAdapter;
import com.manishpateluk.llmrouter.provider.compatible.NvidiaAdapter;
import com.manishpateluk.llmrouter.provider.compatible.OpenRouterAdapter;
import com.manishpateluk.llmrouter.provider.compatible.PerplexityAdapter;
import com.manishpateluk.llmrouter.provider.openai.OpenAiAdapter;
import com.manishpateluk.llmrouter.routing.RouteResolver;
import com.manishpateluk.llmrouter.routing.TokenEstimator;

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
 */
public final class LlmRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);
    private static final ObjectMapper STRUCTURED_OUTPUT_MAPPER = new ObjectMapper();

    private final Map<Provider, ProviderAdapter> adapters;

    /** Detects credentials from the environment (§6) and builds all six built-in provider adapters. */
    public LlmRouter() {
        this(defaultAdapters(new CredentialResolver()));
    }

    /**
     * Builds a router from an explicit set of adapters — bypasses environment-based credential
     * detection entirely. Useful for supplying a custom-configured provider client (e.g. one
     * pointed at Bedrock/Vertex), for restricting the router to a subset of providers, or for
     * tests.
     */
    public LlmRouter(List<ProviderAdapter> adapters) {
        Objects.requireNonNull(adapters, "adapters must not be null");
        Map<Provider, ProviderAdapter> byProvider = new LinkedHashMap<>();
        for (ProviderAdapter adapter : adapters) {
            byProvider.put(adapter.id(), adapter);
        }
        this.adapters = Map.copyOf(byProvider);
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
        validate(request);
        RouterConfig config = resolveConfig(request);
        List<RouteEntry> candidates = resolveCandidates(request, config);

        List<AttemptRecord> attempts = new ArrayList<>();
        for (RouteEntry candidate : candidates) {
            ProviderAdapter adapter = adapters.get(candidate.getProvider());
            if (adapter == null || !adapter.isAvailable()) {
                attempts.add(recordSkip(candidate));
                continue;
            }

            ModelEntry modelEntry = ModelCapabilityTable.findModel(candidate.getProvider(), candidate.getModel()).orElse(null);
            NegotiationResult negotiation = negotiate(modelEntry, request, config);

            try {
                Response fragment = adapter.send(candidate.getModel(), negotiation.getAdaptedRequest());
                return finalizeResponse(request, fragment, candidate, modelEntry, negotiation, attempts);
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
        try {
            validate(request);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        RouterConfig config = resolveConfig(request);
        List<RouteEntry> candidates = resolveCandidates(request, config);
        return attemptAsync(request, candidates, 0, new ArrayList<>());
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

    private CompletableFuture<Response> attemptAsync(Request request, List<RouteEntry> candidates, int index, List<AttemptRecord> attempts) {
        if (index >= candidates.size()) {
            log.error("Router exhausted after {} attempt(s)", attempts.size());
            return CompletableFuture.failedFuture(new RouterExhaustedException(attempts));
        }

        RouteEntry candidate = candidates.get(index);
        ProviderAdapter adapter = adapters.get(candidate.getProvider());
        if (adapter == null || !adapter.isAvailable()) {
            attempts.add(recordSkip(candidate));
            return attemptAsync(request, candidates, index + 1, attempts);
        }

        ModelEntry modelEntry = ModelCapabilityTable.findModel(candidate.getProvider(), candidate.getModel()).orElse(null);
        RouterConfig config = resolveConfig(request);
        NegotiationResult negotiation = negotiate(modelEntry, request, config);

        return adapter.sendAsync(candidate.getModel(), negotiation.getAdaptedRequest())
                .handle((fragment, throwable) -> {
                    if (throwable == null) {
                        return finalizeResponse(request, fragment, candidate, modelEntry, negotiation, attempts);
                    }
                    attempts.add(recordFailure(candidate, unwrap(throwable)));
                    return null;
                })
                .thenCompose(result -> result != null
                        ? CompletableFuture.completedFuture(result)
                        : attemptAsync(request, candidates, index + 1, attempts));
    }

    private List<RouteEntry> resolveCandidates(Request request, RouterConfig config) {
        int promptTokens = TokenEstimator.estimateTokens(request.getPrompt());
        List<RouteEntry> candidates = RouteResolver.resolve(config, promptTokens, availableProviders());
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

    private NegotiationResult negotiate(ModelEntry modelEntry, Request request, RouterConfig config) {
        if (modelEntry == null) {
            // Unknown model (not in the capability table) — nothing to negotiate against; send as-is.
            return NegotiationResult.builder()
                    .adaptedRequest(request)
                    .droppedFeatures(List.of())
                    .structuredOutputViaPromptFallback(false)
                    .build();
        }
        NegotiationResult result = CapabilityNegotiator.negotiate(modelEntry, request, config.getStructuredOutputStrategy());
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
                : fragment.getUsage().toBuilder().estimatedCostUsdCents(estimateCostCents(modelEntry, fragment.getUsage())).build();

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

    private static int estimateCostCents(ModelEntry model, Usage usage) {
        double inputCost = (usage.getInputTokens() / 1_000_000.0) * model.getInputCostPerMillionTokens();
        double outputCost = (usage.getOutputTokens() / 1_000_000.0) * model.getOutputCostPerMillionTokens();
        return (int) Math.round((inputCost + outputCost) * 100.0);
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

    private static AttemptRecord recordSkip(RouteEntry candidate) {
        log.warn("Skipping {}/{}: no credentials available", candidate.getProvider(), candidate.getModel());
        return AttemptRecord.builder()
                .provider(candidate.getProvider())
                .model(candidate.getModel())
                .outcome(AttemptOutcome.SKIPPED)
                .reason("no API key detected")
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
    }
}
