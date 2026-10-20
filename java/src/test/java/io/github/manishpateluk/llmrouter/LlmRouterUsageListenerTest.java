package io.github.manishpateluk.llmrouter;

import static io.github.manishpateluk.llmrouter.ScriptedAdapter.fail;
import static io.github.manishpateluk.llmrouter.ScriptedAdapter.failAfter;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import io.github.manishpateluk.llmrouter.config.RouteEntry;
import io.github.manishpateluk.llmrouter.config.RouterConfig;
import io.github.manishpateluk.llmrouter.config.ThinkingLevel;
import io.github.manishpateluk.llmrouter.error.RouterExhaustedException;
import io.github.manishpateluk.llmrouter.model.AttemptOutcome;
import io.github.manishpateluk.llmrouter.model.AttemptUsage;
import io.github.manishpateluk.llmrouter.model.CallUsage;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.provider.Provider;
import io.github.manishpateluk.llmrouter.provider.compatible.ProviderHttpException;

import org.junit.jupiter.api.Test;

/** {@link UsageListener} (§5.6), against mocked providers. */
class LlmRouterUsageListenerTest {

    private final ScriptedAdapter anthropic = new ScriptedAdapter(Provider.ANTHROPIC);
    private final ScriptedAdapter openai = new ScriptedAdapter(Provider.OPENAI);
    private final Recorder recorder = new Recorder();
    private final LlmRouter router = new LlmRouter(List.of(anthropic, openai)).withUsageListener(recorder);

    /** Collects every event; thread-safe, since async calls report from background threads. */
    static final class Recorder implements UsageListener {

        final List<AttemptUsage> attempts = new CopyOnWriteArrayList<>();
        final List<CallUsage> calls = new CopyOnWriteArrayList<>();

        @Override
        public void onAttempt(AttemptUsage attempt) {
            attempts.add(attempt);
        }

        @Override
        public void onCall(CallUsage call) {
            calls.add(call);
        }
    }

    private static Request request(RouterConfig.RouterConfigBuilder config) {
        return Request.builder().prompt("Hello").config(config.build()).build();
    }

    private static RouterConfig.RouterConfigBuilder route(RouteEntry... entries) {
        return RouterConfig.builder().route(List.of(entries));
    }

    @Test
    void aSuccessfulCallReportsOneAttemptAndTheCall() {
        Response response = router.complete(request(route(RouteEntry.of(Provider.ANTHROPIC, "model-a"))));

        assertThat(recorder.attempts).singleElement().satisfies(attempt -> {
            assertThat(attempt.getProvider()).isEqualTo(Provider.ANTHROPIC);
            assertThat(attempt.getModel()).isEqualTo("model-a");
            assertThat(attempt.getOutcome()).isEqualTo(AttemptOutcome.SUCCESS);
            assertThat(attempt.getThinkingLevel()).isEqualTo(ThinkingLevel.MEDIUM);
            assertThat(attempt.getUsage()).isEqualTo(response.getUsage());
            assertThat(attempt.getLatency()).isPositive();
            assertThat(attempt.isEmbedding()).isFalse();
        });
        assertThat(recorder.calls).singleElement().satisfies(call -> {
            assertThat(call.isSucceeded()).isTrue();
            assertThat(call.getProviderUsed()).isEqualTo(Provider.ANTHROPIC);
            assertThat(call.getModelUsed()).isEqualTo("model-a");
            assertThat(call.getUsage().getInputTokens()).isEqualTo(10);
            assertThat(call.getAttempts()).containsExactlyElementsOf(recorder.attempts);
            assertThat(call.getLatency()).isGreaterThanOrEqualTo(recorder.attempts.get(0).getLatency());
            assertThat(call.getFailure()).isNull();
        });
    }

    @Test
    void skippedAndFailedCandidatesAreReportedInOrderBeforeTheOneThatAnswered() {
        anthropic.script("model-a", fail(new ProviderHttpException(400, "bad request")));
        router.complete(request(route(
                RouteEntry.of(Provider.OPENROUTER, "absent"),
                RouteEntry.of(Provider.ANTHROPIC, "model-a"),
                RouteEntry.of(Provider.OPENAI, "model-b"))));

        assertThat(recorder.attempts).extracting(AttemptUsage::getModel, AttemptUsage::getOutcome).containsExactly(
                tuple("absent", AttemptOutcome.SKIPPED),
                tuple("model-a", AttemptOutcome.FAILED),
                tuple("model-b", AttemptOutcome.SUCCESS));
        assertThat(recorder.attempts.get(0).getLatency()).isZero();
        assertThat(recorder.attempts.get(0).getReason()).isEqualTo("no API key detected");
        assertThat(recorder.attempts.get(1).getReason()).contains("bad request");
        assertThat(recorder.attempts.get(1).getUsage()).isNull();
        assertThat(recorder.calls).singleElement().satisfies(call -> assertThat(call.getAttempts()).hasSize(3));
    }

    @Test
    void anExhaustedCallReportsItsFailure() {
        anthropic.script("model-a", fail(new ProviderHttpException(400, "bad request")));

        assertThatThrownBy(() -> router.complete(request(route(RouteEntry.of(Provider.ANTHROPIC, "model-a")))))
                .isInstanceOf(RouterExhaustedException.class);

        assertThat(recorder.calls).singleElement().satisfies(call -> {
            assertThat(call.isSucceeded()).isFalse();
            assertThat(call.getProviderUsed()).isNull();
            assertThat(call.getUsage()).isNull();
            assertThat(call.getFailure()).isInstanceOf(RouterExhaustedException.class);
            assertThat(call.getAttempts()).singleElement()
                    .satisfies(attempt -> assertThat(attempt.getOutcome()).isEqualTo(AttemptOutcome.FAILED));
        });
    }

    @Test
    void retriesAreCountedOnTheAttemptAndIncludedInItsLatency() {
        anthropic.script("model-a", fail(new ProviderHttpException(503, "busy")), ScriptedAdapter.ok());

        router.complete(request(route(RouteEntry.of(Provider.ANTHROPIC, "model-a")).maxRetries(1).retryDelay(Duration.ofMillis(50))));

        assertThat(recorder.attempts).singleElement().satisfies(attempt -> {
            assertThat(attempt.getOutcome()).isEqualTo(AttemptOutcome.SUCCESS);
            assertThat(attempt.getRetries()).isEqualTo(1);
            assertThat(attempt.getLatency()).isGreaterThanOrEqualTo(Duration.ofMillis(50));
        });
    }

    @Test
    void asyncCallsReportTheSameEvents() throws Exception {
        anthropic.script("model-a", fail(new ProviderHttpException(503, "busy")), ScriptedAdapter.ok());

        router.completeAsync(request(route(RouteEntry.of(Provider.ANTHROPIC, "model-a"), RouteEntry.of(Provider.OPENAI, "model-b"))
                        .maxRetries(1).retryDelay(Duration.ofMillis(10))))
                .get(5, TimeUnit.SECONDS);

        assertThat(recorder.attempts).singleElement().satisfies(attempt -> {
            assertThat(attempt.getModel()).isEqualTo("model-a");
            assertThat(attempt.getRetries()).isEqualTo(1);
            assertThat(attempt.getUsage()).isNotNull();
        });
        assertThat(recorder.calls).singleElement().satisfies(call -> assertThat(call.isSucceeded()).isTrue());
    }

    @Test
    void anAsyncExhaustedCallReportsItsFailure() {
        anthropic.script("model-a", fail(new ProviderHttpException(400, "bad request")));

        assertThatThrownBy(() -> router.completeAsync(request(route(RouteEntry.of(Provider.ANTHROPIC, "model-a"))))
                .get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(RouterExhaustedException.class);

        assertThat(recorder.calls).singleElement().satisfies(call -> {
            assertThat(call.isSucceeded()).isFalse();
            assertThat(call.getFailure()).isInstanceOf(RouterExhaustedException.class);
        });
    }

    @Test
    void aStreamListenerFailureEndsTheCallAndIsReportedAsItsFailure() {
        IllegalStateException broken = new IllegalStateException("listener broke");

        assertThatThrownBy(() -> router.completeStreaming(request(route(RouteEntry.of(Provider.ANTHROPIC, "model-a"))),
                text -> {
                    throw broken;
                })).isSameAs(broken);

        assertThat(recorder.calls).singleElement().satisfies(call -> {
            assertThat(call.isSucceeded()).isFalse();
            assertThat(call.getFailure()).isSameAs(broken);
        });
    }

    @Test
    void streamingCallsReportTheAttemptThatAnsweredAfterAReset() {
        anthropic.script("model-a", failAfter("partial", new ProviderHttpException(500, "boom")));

        router.completeStreaming(request(route(RouteEntry.of(Provider.ANTHROPIC, "model-a"), RouteEntry.of(Provider.OPENAI, "model-b"))),
                text -> { });

        assertThat(recorder.attempts).extracting(AttemptUsage::getOutcome)
                .containsExactly(AttemptOutcome.FAILED, AttemptOutcome.SUCCESS);
    }

    @Test
    void aListenerThatThrowsNeverFailsTheCall() {
        LlmRouter broken = router.withUsageListener(new UsageListener() {
            @Override
            public void onAttempt(AttemptUsage attempt) {
                throw new IllegalStateException("metrics down");
            }

            @Override
            public void onCall(CallUsage call) {
                throw new IllegalStateException("metrics down");
            }
        });

        Response response = broken.complete(request(route(RouteEntry.of(Provider.ANTHROPIC, "model-a"))));

        assertThat(response.getContent()).isEqualTo("ok from model-a");
    }

    @Test
    void withUsageListenerLeavesTheOriginalRouterSilent() {
        LlmRouter plain = new LlmRouter(List.of(anthropic));
        plain.withUsageListener(recorder);

        plain.complete(request(route(RouteEntry.of(Provider.ANTHROPIC, "model-a"))));

        assertThat(recorder.attempts).isEmpty();
        assertThat(recorder.calls).isEmpty();
        assertThatThrownBy(() -> plain.withUsageListener(null)).isInstanceOf(NullPointerException.class);
    }
}
