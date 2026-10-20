package io.github.manishpateluk.llmrouter;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.github.manishpateluk.llmrouter.CallDeadline.AttemptLimit;
import io.github.manishpateluk.llmrouter.capability.ModelCapabilityTable;
import io.github.manishpateluk.llmrouter.capability.ModelEntry;
import io.github.manishpateluk.llmrouter.config.CooldownPolicy;
import io.github.manishpateluk.llmrouter.config.FallbackPolicy;
import io.github.manishpateluk.llmrouter.config.Feature;
import io.github.manishpateluk.llmrouter.config.RouteEntry;
import io.github.manishpateluk.llmrouter.config.RouterConfig;
import io.github.manishpateluk.llmrouter.config.StructuredOutputStrategy;
import io.github.manishpateluk.llmrouter.config.ThinkingLevel;
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
import io.github.manishpateluk.llmrouter.provider.openai.OpenAiAdapter;
import io.github.manishpateluk.llmrouter.provider.perplexity.PerplexityAdapter;
import io.github.manishpateluk.llmrouter.routing.RequestEstimator;
import io.github.manishpateluk.llmrouter.routing.RouteResolver;
import io.github.manishpateluk.llmrouter.routing.RouteResolver.Candidate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The router's single entry point — see {@code LIBRARY_SPEC.md} §1-§2. Construct one and reuse
 * it: credential detection happens once, when this instance is built, and the instance is safe
 * to share across threads (e.g. as a singleton bean in a Spring application). Every method here
 * is stateless over the request it's given, except that with {@code RouterConfig.cooldown} set,
 * the instance remembers which models keep failing (§5.5).
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
    private final Cooldowns cooldowns;

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
        this(adapters, requestInterceptor, Clock.systemUTC());
    }

    /** As {@link #LlmRouter(List, RequestInterceptor)}, with {@code clock} timing cooldowns — for tests. */
    LlmRouter(List<ProviderAdapter> adapters, RequestInterceptor requestInterceptor, Clock clock) {
        Objects.requireNonNull(adapters, "adapters must not be null");
        Objects.requireNonNull(requestInterceptor, "requestInterceptor must not be null");
        Map<Provider, ProviderAdapter> byProvider = new LinkedHashMap<>();
        for (ProviderAdapter adapter : adapters) {
            byProvider.put(adapter.id(), adapter);
        }
        this.adapters = Map.copyOf(byProvider);
        this.requestInterceptor = requestInterceptor;
        this.cooldowns = new Cooldowns(clock);
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
        return completeVia(request, (adapter, model, toSend, limit) -> callWithin(limit, () -> adapter.send(model, toSend)));
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
            return completeVia(request, (adapter, model, toSend, limit) -> {
                StreamingAttempt attempt = new StreamingAttempt(listener);
                try {
                    return callWithin(limit, () -> adapter.sendStreaming(model, toSend, attempt::deliver));
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
        return completeAsyncVia(request, (adapter, model, toSend, limit) -> {
            StreamingAttempt attempt = new StreamingAttempt(listener);
            CompletableFuture<Response> sent;
            try {
                sent = boundWithin(limit, adapter.sendStreamingAsync(model, toSend, attempt::deliver));
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
     * Once the attempt has failed it forwards nothing more, so text from an attempt abandoned on a
     * timeout can't reach the listener after its reset.
     */
    private static final class StreamingAttempt {

        private final StreamListener listener;
        private boolean streamed;
        private boolean closed;

        StreamingAttempt(StreamListener listener) {
            this.listener = listener;
        }

        synchronized void deliver(String delta) {
            if (closed || delta == null || delta.isEmpty()) {
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
        synchronized void failed(Throwable e) {
            closed = true;
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

    /** How one attempt is sent: plain or streaming, within {@code limit} ({@code null} for none, §5.5). */
    @FunctionalInterface
    private interface Sender {
        Response send(ProviderAdapter adapter, String model, Request toSend, AttemptLimit limit);
    }

    /** The shared sync fallback loop behind {@link #complete(Request)} and {@link #completeStreaming}. */
    private Response completeVia(Request request, Sender sender) {
        validate(request);
        RouterConfig config = resolveConfig(request);
        CallDeadline deadline = CallDeadline.start(config.getCallTimeout());
        CandidatePlan plan = new CandidatePlan(request, config);

        List<AttemptRecord> attempts = new ArrayList<>();
        for (Candidate resolved = plan.next(); resolved != null; resolved = plan.next()) {
            if (deadline.expired()) {
                return throwCallTimedOut(deadline, attempts);
            }
            RouteEntry candidate = resolved.entry();
            ThinkingLevel level = resolved.thinkingLevel();
            ProviderAdapter adapter = adapters.get(candidate.getProvider());
            ModelEntry modelEntry = findModel(candidate);
            String schemaProblem = schemaProblem(adapter, candidate, request);
            String skipReason = skipReason(adapter, resolved, modelEntry, request, config, schemaProblem);
            if (skipReason != null) {
                attempts.add(recordSkip(candidate, level, skipReason));
                continue;
            }

            NegotiationResult negotiation = negotiate(candidate, level, modelEntry, request, config, schemaProblem);

            Request toSend;
            try {
                toSend = requestInterceptor.beforeSend(candidate.getProvider(), candidate.getModel(), negotiation.getAdaptedRequest());
            } catch (RuntimeException e) {
                attempts.add(recordFailure(candidate, level, e, 0));
                continue;
            }

            for (int retries = 0; ; retries++) {
                try {
                    Response fragment = sender.send(adapter, candidate.getModel(), toSend, deadline.limitFor(config.getAttemptTimeout()));
                    recordSuccess(config, candidate);
                    return finalizeResponse(request, fragment, resolved, modelEntry, negotiation, attempts);
                } catch (ListenerFailure e) {
                    throw e; // the caller's listener broke, not the provider — never fall back on it
                } catch (RuntimeException e) {
                    Duration delay = retryDelay(adapter, candidate, e, retries, config, deadline);
                    if (delay == null || !sleep(delay)) {
                        attempts.add(recordFailure(candidate, level, e, retries));
                        recordFailure(config, candidate);
                        break;
                    }
                }
            }
        }

        if (deadline.expired()) {
            return throwCallTimedOut(deadline, attempts);
        }
        log.error("Router exhausted after {} attempt(s)", attempts.size());
        throw new RouterExhaustedException(attempts);
    }

    /** Ends a call whose {@code callTimeout} ran out (§5.5); declared to return so callers can {@code return} it. */
    private static <T> T throwCallTimedOut(CallDeadline deadline, List<AttemptRecord> attempts) {
        log.error("Call timeout of {} reached after {} attempt(s)", CallDeadline.format(deadline.callTimeout()), attempts.size());
        throw new RouterExhaustedException(attempts);
    }

    /**
     * The wait before retrying {@code candidate} after {@code failure}, or {@code null} to fall back
     * instead (§5.5): no retries left, a timeout (retrying it would only wait as long again), a
     * failure its adapter doesn't consider transient, or no time left on the call for the wait.
     */
    private static Duration retryDelay(
            ProviderAdapter adapter, RouteEntry candidate, Throwable failure, int retriesSoFar, RouterConfig config,
            CallDeadline deadline) {
        if (retriesSoFar >= config.getMaxRetries() || failure instanceof AttemptTimeoutException
                || !adapter.isTransient(failure)) {
            return null;
        }
        Duration delay = Retries.delayBeforeRetry(retriesSoFar, config.getRetryDelay(), adapter.retryAfter(failure),
                deadline.remaining());
        if (delay != null) {
            log.warn("Retrying {}/{} in {} ms (retry {} of {}) after: {}", candidate.getProvider(), candidate.getModel(),
                    delay.toMillis(), retriesSoFar + 1, config.getMaxRetries(), failure.getMessage());
        }
        return delay;
    }

    /** Sleeps for {@code delay}; {@code false} if interrupted, with the interrupt flag restored. */
    private static boolean sleep(Duration delay) {
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void recordSuccess(RouterConfig config, RouteEntry candidate) {
        if (config.getCooldown() != null) {
            cooldowns.recordSuccess(candidate.getProvider(), candidate.getModel());
        }
    }

    private void recordFailure(RouterConfig config, RouteEntry candidate) {
        if (config.getCooldown() != null) {
            cooldowns.recordFailure(candidate.getProvider(), candidate.getModel(), config.getCooldown());
        }
    }

    /**
     * Runs {@code call} on this thread when there's no {@code limit}, exactly as before timeouts
     * existed; otherwise on a virtual thread, interrupting it and throwing
     * {@link AttemptTimeoutException} if it outlasts the limit (§5.5).
     */
    private static Response callWithin(AttemptLimit limit, Supplier<Response> call) {
        if (limit == null) {
            return call.get();
        }
        Future<Response> future = ProviderAdapter.DEFAULT_ASYNC_EXECUTOR.submit(call::get);
        try {
            return future.get(limit.bound().toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new AttemptTimeoutException(limit.timeoutReason());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new CompletionException(cause);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the provider", e);
        }
    }

    /**
     * {@code sent}, or a future that fails with {@link AttemptTimeoutException} if {@code sent}
     * hasn't completed within {@code limit} (§5.5). {@code sent} is then cancelled; work the
     * adapter already started may still run to the end, but its result is ignored.
     */
    private static CompletableFuture<Response> boundWithin(AttemptLimit limit, CompletableFuture<Response> sent) {
        if (limit == null) {
            return sent;
        }
        CompletableFuture<Response> bounded = new CompletableFuture<>();
        sent.whenComplete((response, throwable) -> {
            if (throwable == null) {
                bounded.complete(response);
            } else {
                bounded.completeExceptionally(unwrap(throwable));
            }
        });
        CompletableFuture.delayedExecutor(limit.bound().toNanos(), TimeUnit.NANOSECONDS).execute(() -> {
            if (bounded.completeExceptionally(new AttemptTimeoutException(limit.timeoutReason()))) {
                sent.cancel(true);
            }
        });
        return bounded;
    }

    /** An attempt that ran past {@code attemptTimeout} or the call's {@code callTimeout}. */
    private static final class AttemptTimeoutException extends RuntimeException {

        AttemptTimeoutException(String message) {
            super(message);
        }
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
        return completeAsyncVia(request, (adapter, model, toSend, limit) -> boundWithin(limit, adapter.sendAsync(model, toSend)));
    }

    /** How one async attempt is sent: plain or streaming, within {@code limit} ({@code null} for none, §5.5). */
    @FunctionalInterface
    private interface AsyncSender {
        CompletableFuture<Response> send(ProviderAdapter adapter, String model, Request toSend, AttemptLimit limit);
    }

    /** The shared async fallback loop behind {@link #completeAsync(Request)} and {@link #completeStreamingAsync}. */
    private CompletableFuture<Response> completeAsyncVia(Request request, AsyncSender sender) {
        try {
            validate(request);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        RouterConfig config = resolveConfig(request);
        CallDeadline deadline = CallDeadline.start(config.getCallTimeout());
        CandidatePlan plan = new CandidatePlan(request, config);
        return attemptAsync(request, plan, new ArrayList<>(), sender, deadline);
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
            Request request, CandidatePlan plan, List<AttemptRecord> attempts, AsyncSender sender, CallDeadline deadline) {
        Candidate resolved = deadline.expired() ? null : plan.next();
        if (resolved == null) {
            try {
                if (deadline.expired()) {
                    return throwCallTimedOut(deadline, attempts);
                }
                log.error("Router exhausted after {} attempt(s)", attempts.size());
                throw new RouterExhaustedException(attempts);
            } catch (RouterExhaustedException e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        RouteEntry candidate = resolved.entry();
        ThinkingLevel level = resolved.thinkingLevel();
        ProviderAdapter adapter = adapters.get(candidate.getProvider());
        ModelEntry modelEntry = findModel(candidate);
        RouterConfig config = resolveConfig(request);
        String schemaProblem = schemaProblem(adapter, candidate, request);
        String skipReason = skipReason(adapter, resolved, modelEntry, request, config, schemaProblem);
        if (skipReason != null) {
            attempts.add(recordSkip(candidate, level, skipReason));
            return attemptAsync(request, plan, attempts, sender, deadline);
        }

        NegotiationResult negotiation = negotiate(candidate, level, modelEntry, request, config, schemaProblem);

        Request toSend;
        try {
            toSend = requestInterceptor.beforeSend(candidate.getProvider(), candidate.getModel(), negotiation.getAdaptedRequest());
        } catch (RuntimeException e) {
            attempts.add(recordFailure(candidate, level, e, 0));
            return attemptAsync(request, plan, attempts, sender, deadline);
        }

        return sendWithRetriesAsync(adapter, candidate, toSend, sender, config, deadline, 0)
                .handle((fragment, throwable) -> {
                    if (throwable == null) {
                        recordSuccess(config, candidate);
                        return finalizeResponse(request, fragment, resolved, modelEntry, negotiation, attempts);
                    }
                    Throwable cause = unwrap(throwable);
                    if (cause instanceof ListenerFailure listenerFailure) {
                        // the caller's listener broke, not the provider — never fall back on it
                        throw new CompletionException(listenerFailure.listenerException());
                    }
                    FailedAfterRetries failed = (FailedAfterRetries) cause;
                    attempts.add(recordFailure(candidate, level, failed.getCause(), failed.retries));
                    recordFailure(config, candidate);
                    return null;
                })
                .thenCompose(result -> result != null
                        ? CompletableFuture.completedFuture(result)
                        : attemptAsync(request, plan, attempts, sender, deadline));
    }

    /**
     * Sends one async attempt, retrying transient failures per {@code RouterConfig.maxRetries}
     * (§5.5). Fails with {@link FailedAfterRetries} once it gives up, or with the
     * {@link ListenerFailure} unchanged.
     */
    private CompletableFuture<Response> sendWithRetriesAsync(
            ProviderAdapter adapter, RouteEntry candidate, Request toSend, AsyncSender sender, RouterConfig config,
            CallDeadline deadline, int retries) {
        CompletableFuture<Response> sent;
        try {
            sent = sender.send(adapter, candidate.getModel(), toSend, deadline.limitFor(config.getAttemptTimeout()));
        } catch (RuntimeException e) {
            sent = CompletableFuture.failedFuture(e);
        }
        return sent.handle((fragment, throwable) -> {
            if (throwable == null) {
                return CompletableFuture.completedFuture(fragment);
            }
            Throwable cause = unwrap(throwable);
            if (cause instanceof ListenerFailure) {
                return CompletableFuture.<Response>failedFuture(cause);
            }
            Duration delay = retryDelay(adapter, candidate, cause, retries, config, deadline);
            if (delay == null) {
                return CompletableFuture.<Response>failedFuture(new FailedAfterRetries(cause, retries));
            }
            return CompletableFuture.runAsync(() -> { }, CompletableFuture.delayedExecutor(delay.toNanos(), TimeUnit.NANOSECONDS))
                    .thenCompose(ignored -> sendWithRetriesAsync(adapter, candidate, toSend, sender, config, deadline, retries + 1));
        }).thenCompose(future -> future);
    }

    /** Carries an async attempt's final failure, and the retries made before it, out of {@link #sendWithRetriesAsync}. */
    private static final class FailedAfterRetries extends RuntimeException {

        private final int retries;

        FailedAfterRetries(Throwable cause, int retries) {
            super(cause.getMessage(), cause);
            this.retries = retries;
        }
    }

    /**
     * The candidates one call tries, in order, resolved a thinking level at a time: the whole
     * route at the requested level first, then — under {@link FallbackPolicy#FINISH}, and only
     * once those are used up — the route's provider-only entries (or the default route) at each
     * lower level down to {@code LOW} (§5.1.2). Lower levels are resolved only when reached, and a
     * provider/model already tried at a higher level isn't tried again. Not thread-safe; one call
     * walks it in order.
     */
    private final class CandidatePlan {

        private final Request request;
        private final RouterConfig config;
        private final Deque<Candidate> pending;
        private final Set<String> seen = new HashSet<>();
        private ThinkingLevel level;

        CandidatePlan(Request request, RouterConfig config) {
            this.request = request;
            this.config = config;
            this.level = config.getThinkingLevel();
            List<Candidate> first = resolveAt(level, false);
            if (first.isEmpty() && config.getRoute() == null) {
                throw new NoProvidersConfiguredException(
                        "No provider credentials were detected. Set at least one provider's API key "
                                + "(see LIBRARY_SPEC.md §6.1) before calling the router.");
            }
            this.pending = new ArrayDeque<>(first);
        }

        /** The next candidate to try, or {@code null} once every level allowed has been used up. */
        Candidate next() {
            while (true) {
                Candidate candidate = pending.poll();
                if (candidate != null) {
                    if (seen.add(candidate.entry().getProvider() + "/" + candidate.entry().getModel())) {
                        return candidate;
                    }
                    continue;
                }
                if (config.getFallback() != FallbackPolicy.FINISH || level.lower() == null) {
                    return null;
                }
                ThinkingLevel exhausted = level;
                level = level.lower();
                log.info("Every candidate at thinking level {} failed or was skipped; trying {}", exhausted, level);
                pending.addAll(resolveAt(level, true));
            }
        }

        private List<Candidate> resolveAt(ThinkingLevel at, boolean providerOnly) {
            List<Candidate> candidates = RouteResolver.resolveCandidates(config, at, availableProviders(),
                    model -> ineligibleReason(model, request, config),
                    model -> RequestEstimator.estimatedCostUsd(request, model),
                    providerOnly);
            log.debug("Resolved {} candidate(s) at thinking level {}: {}", candidates.size(), at, candidates);
            return candidates;
        }
    }

    /**
     * Why {@code model} can't serve {@code request} — it's cooling down after repeated failures
     * (§5.5), a required feature it can't honor (§5.4), or a context window too small for the
     * estimated request (§5.3) — or {@code null} if it can.
     */
    private String ineligibleReason(ModelEntry model, Request request, RouterConfig config) {
        String coolingDown = coolingDownReason(config, model.getProvider(), model.getModel());
        if (coolingDown != null) {
            return coolingDown;
        }
        List<Feature> unmet = unmetRequiredFeatures(model, request, config);
        if (!unmet.isEmpty()) {
            return "does not support required feature(s) " + unmet;
        }
        return contextWindowProblem(model, request);
    }

    /**
     * Why {@code request} won't fit {@code model}'s context window, or {@code null} if it will
     * (§5.3). Never a problem when a {@link RequestInterceptor} is installed: it sees the request
     * after this check and may shrink it to fit (e.g. by compressing history), so turning the model
     * away beforehand would be wrong.
     */
    private String contextWindowProblem(ModelEntry model, Request request) {
        if (requestInterceptor != NO_OP_INTERCEPTOR || model.getContextWindowTokens() <= 0) {
            return null;
        }
        int inputTokens = RequestEstimator.inputTokens(request, model);
        return inputTokens > model.getContextWindowTokens()
                ? "estimated request (~" + inputTokens + " tokens) exceeds the context window (" + model.getContextWindowTokens() + " tokens)"
                : null;
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
     * be tried: no credentials (§5.1), cooling down after repeated failures (§5.5), a context
     * window too small for the request (§5.3), or it
     * can't honor one of {@code requiredFeatures} (§5.4) — including a required
     * {@code responseSchema} its adapter can't enforce natively ({@code schemaProblem}) when there's
     * no prompt fallback. A model missing from the capability table is skipped only when the request
     * uses a required feature, since there's no data to verify it; otherwise it's tried
     * optimistically.
     */
    private String skipReason(
            ProviderAdapter adapter, Candidate resolved, ModelEntry modelEntry, Request request, RouterConfig config,
            String schemaProblem) {
        RouteEntry candidate = resolved.entry();
        if (adapter == null || !adapter.isAvailable()) {
            return "no API key detected";
        }
        if (candidate.isProviderOnly()) {
            // RouteResolver leaves a provider-only entry unexpanded only when none of its models qualified.
            return unresolvedReason(resolved, request, config);
        }
        String coolingDown = coolingDownReason(config, candidate.getProvider(), candidate.getModel());
        if (coolingDown != null) {
            return coolingDown;
        }
        if (modelEntry == null) {
            List<Feature> required = requiredFeaturesInUse(request, config);
            return required.isEmpty()
                    ? null
                    : "model is not in the capability table, so required feature(s) " + required + " can't be verified";
        }
        String contextProblem = contextWindowProblem(modelEntry, request);
        if (contextProblem != null) {
            return contextProblem;
        }
        List<Feature> unmet = CapabilityNegotiator.unmetRequiredFeatures(modelEntry, request,
                config.getStructuredOutputStrategy(), config.getRequiredFeatures(), schemaProblem == null);
        if (unmet.isEmpty()) {
            return null;
        }
        String reason = "model does not support required feature(s) " + unmet;
        return schemaProblem != null && unmet.contains(Feature.RESPONSE_SCHEMA)
                ? reason + ": can't enforce this responseSchema natively (" + schemaProblem + ")"
                : reason;
    }

    /**
     * The skip reason for a provider-only entry none of whose models qualified: the usual
     * {@code no <provider> model supports required feature(s) [...]} when every model fell short on
     * required features alone, otherwise the resolver's list of reasons.
     */
    private static String unresolvedReason(Candidate resolved, Request request, RouterConfig config) {
        Provider provider = resolved.entry().getProvider();
        boolean featuresAlone = ModelCapabilityTable.listModels(provider).stream()
                .allMatch(model -> !unmetRequiredFeatures(model, request, config).isEmpty());
        return featuresAlone
                ? "no " + provider + " model supports required feature(s) " + requiredFeaturesInUse(request, config)
                : resolved.unresolvedReason();
    }

    /** Why {@code provider}/{@code model} is cooling down, or {@code null} if it isn't or cooldown is off (§5.5). */
    private String coolingDownReason(RouterConfig config, Provider provider, String model) {
        return config.getCooldown() == null ? null : cooldowns.coolingDownReason(provider, model);
    }

    /** Why {@code candidate}'s adapter can't enforce the request's {@code responseSchema} natively, or {@code null}. */
    private static String schemaProblem(ProviderAdapter adapter, RouteEntry candidate, Request request) {
        if (adapter == null || request.getResponseSchema() == null) {
            return null;
        }
        return adapter.responseSchemaProblem(candidate.getModel(), request.getResponseSchema());
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

    private NegotiationResult negotiate(
            RouteEntry candidate, ThinkingLevel level, ModelEntry modelEntry, Request request, RouterConfig config,
            String schemaProblem) {
        if (schemaProblem != null) {
            log.warn("{}/{} can't enforce this responseSchema natively ({}); {}", candidate.getProvider(), candidate.getModel(),
                    schemaProblem, config.getStructuredOutputStrategy() == StructuredOutputStrategy.NATIVE
                            ? "dropping it" : "putting it in the prompt instead");
        }
        if (modelEntry == null) {
            // Unknown model (not in the capability table) — nothing to negotiate against; send as-is,
            // optimistically forwarding temperature/topP since there's no capability data to check them against.
            return CapabilityNegotiator.negotiateUnknownModel(request, config.getStructuredOutputStrategy(),
                    config.getTemperature(), config.getTopP(), schemaProblem == null);
        }
        NegotiationResult result = CapabilityNegotiator.negotiate(modelEntry, request, config.getStructuredOutputStrategy(),
                config.getTemperature(), config.getTopP(), schemaProblem == null, config.isSendThinkingLevel() ? level : null);
        if (!result.getDroppedFeatures().isEmpty()) {
            log.warn("Dropped features {} for {}/{} due to capability mismatch{}",
                    result.getDroppedFeatures(), modelEntry.getProvider(), modelEntry.getModel(),
                    result.getDroppedAttachments().isEmpty() ? "" : "; attachments: " + result.getDroppedAttachments());
        }
        return result;
    }

    private Response finalizeResponse(
            Request originalRequest,
            Response fragment,
            Candidate resolved,
            ModelEntry modelEntry,
            NegotiationResult negotiation,
            List<AttemptRecord> priorAttempts) {

        RouteEntry candidate = resolved.entry();
        // A cost the provider reported itself (Perplexity's, which includes search fees) wins over the table's estimate.
        boolean providerReportedCost = fragment.getUsage().getEstimatedCostUsdMicros() > 0;
        Usage usage = modelEntry == null || providerReportedCost
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
                .droppedAttachments(negotiation.getDroppedAttachments())
                .attempts(List.copyOf(priorAttempts))
                .thinkingLevel(resolved.thinkingLevel())
                .reasoning(negotiation.getReasoning())
                .build();

        log.info("Served by {}/{} at thinking level {} ({} input, {} output tokens, {} cent(s) estimated)",
                candidate.getProvider(), candidate.getModel(), resolved.thinkingLevel(), usage.getInputTokens(),
                usage.getOutputTokens(), usage.getEstimatedCostUsdCents());
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
        return recordSkip(candidate, null, reason);
    }

    private static AttemptRecord recordSkip(RouteEntry candidate, ThinkingLevel level, String reason) {
        log.warn("Skipping {}/{}: {}", candidate.getProvider(), candidate.getModel(), reason);
        return AttemptRecord.builder()
                .provider(candidate.getProvider())
                .model(candidate.getModel())
                .outcome(AttemptOutcome.SKIPPED)
                .reason(reason)
                .thinkingLevel(level)
                .build();
    }

    private static AttemptRecord recordFailure(RouteEntry candidate, Throwable e) {
        return recordFailure(candidate, null, e, 0);
    }

    private static AttemptRecord recordFailure(RouteEntry candidate, ThinkingLevel level, Throwable e, int retries) {
        log.error("Attempt failed for {}/{}{}: {}", candidate.getProvider(), candidate.getModel(),
                retries == 0 ? "" : " after " + retries + " retries", e.getMessage());
        return AttemptRecord.builder()
                .provider(candidate.getProvider())
                .model(candidate.getModel())
                .outcome(AttemptOutcome.FAILED)
                .reason(e.getMessage())
                .thinkingLevel(level)
                .retries(retries)
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
        if (config.getFallback() == null) {
            throw new InvalidConfigException("RouterConfig.fallback must not be null");
        }
        requirePositive(config.getAttemptTimeout(), "RouterConfig.attemptTimeout");
        requirePositive(config.getCallTimeout(), "RouterConfig.callTimeout");
        if (config.getMaxRetries() < 0) {
            throw new InvalidConfigException("RouterConfig.maxRetries must not be negative, was " + config.getMaxRetries());
        }
        if (config.getRetryDelay() == null || config.getRetryDelay().isNegative()) {
            throw new InvalidConfigException("RouterConfig.retryDelay must not be null or negative");
        }
        CooldownPolicy cooldown = config.getCooldown();
        if (cooldown != null) {
            if (cooldown.getFailures() < 1) {
                throw new InvalidConfigException("RouterConfig.cooldown.failures must be at least 1, was " + cooldown.getFailures());
            }
            if (cooldown.getDuration() == null) {
                throw new InvalidConfigException("RouterConfig.cooldown.duration must not be null");
            }
            requirePositive(cooldown.getDuration(), "RouterConfig.cooldown.duration");
        }
        if (request.getMaxOutputTokens() != null && request.getMaxOutputTokens() <= 0) {
            throw new IllegalArgumentException("Request.maxOutputTokens must be positive when set, was "
                    + request.getMaxOutputTokens());
        }
        List<String> toolProblems = ToolDefinitionValidator.validate(request.getTools());
        if (!toolProblems.isEmpty()) {
            throw new InvalidRequestException("Invalid tool definitions:\n  - " + String.join("\n  - ", toolProblems));
        }
    }

    /** {@code duration} may be {@code null} (unset), but if set must be positive. */
    private static void requirePositive(Duration duration, String field) {
        if (duration != null && (duration.isZero() || duration.isNegative())) {
            throw new InvalidConfigException(field + " must be positive when set, was " + duration);
        }
    }
}
