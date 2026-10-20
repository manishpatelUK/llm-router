package io.github.manishpateluk.llmrouter;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.model.Usage;
import io.github.manishpateluk.llmrouter.provider.Provider;
import io.github.manishpateluk.llmrouter.provider.ProviderAdapter;

/**
 * A mocked provider for resilience tests: each model answers from a script of steps (succeed,
 * fail, be slow), one step per call, repeating the last step once the script runs out. A model
 * with no script answers "ok from {model}". Records every call, its thread, and whether a slow
 * step was interrupted. Uses {@link ProviderAdapter}'s default {@code isTransient}/{@code retryAfter}.
 */
final class ScriptedAdapter implements ProviderAdapter {

    /** One call's behaviour. */
    sealed interface Step permits Succeed, Fail, Slow {
    }

    record Succeed() implements Step {
    }

    /** Streams {@code partial} (if not null), then throws {@code error}. */
    record Fail(String partial, RuntimeException error) implements Step {
    }

    /** Streams {@code partial} (if not null), waits {@code delay}, then streams {@code late} and succeeds. */
    record Slow(Duration delay, String partial, String late) implements Step {
    }

    record Call(String model, Request request, Thread thread, long atNanos) {
    }

    static Step ok() {
        return new Succeed();
    }

    static Step fail(RuntimeException error) {
        return new Fail(null, error);
    }

    static Step failAfter(String partial, RuntimeException error) {
        return new Fail(partial, error);
    }

    static Step slow(Duration delay) {
        return new Slow(delay, null, null);
    }

    private final Provider provider;
    private final Map<String, Deque<Step>> scripts = new ConcurrentHashMap<>();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private volatile boolean interrupted;

    ScriptedAdapter(Provider provider) {
        this.provider = provider;
    }

    ScriptedAdapter script(String model, Step... steps) {
        scripts.put(model, new ArrayDeque<>(List.of(steps)));
        return this;
    }

    List<Call> calls() {
        return calls;
    }

    List<String> models() {
        return calls.stream().map(Call::model).toList();
    }

    long callsTo(String model) {
        return calls.stream().filter(call -> call.model().equals(model)).count();
    }

    /** Whether a slow step was interrupted while it waited. */
    boolean wasInterrupted() {
        return interrupted;
    }

    @Override
    public Provider id() {
        return provider;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public Response send(String model, Request adaptedRequest) {
        return sendStreaming(model, adaptedRequest, text -> { });
    }

    @Override
    public Response sendStreaming(String model, Request adaptedRequest, Consumer<String> onText) {
        calls.add(new Call(model, adaptedRequest, Thread.currentThread(), System.nanoTime()));
        Step step = nextStep(model);
        switch (step) {
            case Fail fail -> {
                if (fail.partial() != null) {
                    onText.accept(fail.partial());
                }
                throw fail.error();
            }
            case Slow slow -> {
                if (slow.partial() != null) {
                    onText.accept(slow.partial());
                }
                try {
                    Thread.sleep(slow.delay());
                } catch (InterruptedException e) {
                    interrupted = true;
                }
                if (slow.late() != null) {
                    onText.accept(slow.late());
                }
            }
            case Succeed succeed -> {
            }
        }
        String content = "ok from " + model;
        onText.accept(content);
        return Response.builder()
                .content(content)
                .usage(Usage.builder().inputTokens(10).outputTokens(5).build())
                .build();
    }

    private Step nextStep(String model) {
        Deque<Step> script = scripts.get(model);
        if (script == null || script.isEmpty()) {
            return new Succeed();
        }
        synchronized (script) {
            return script.size() > 1 ? script.poll() : script.peek();
        }
    }
}
