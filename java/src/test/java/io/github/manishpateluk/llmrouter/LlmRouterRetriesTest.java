package io.github.manishpateluk.llmrouter;

import static io.github.manishpateluk.llmrouter.ScriptedAdapter.fail;
import static io.github.manishpateluk.llmrouter.ScriptedAdapter.failAfter;
import static io.github.manishpateluk.llmrouter.ScriptedAdapter.ok;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import io.github.manishpateluk.llmrouter.config.RouteEntry;
import io.github.manishpateluk.llmrouter.config.RouterConfig;
import io.github.manishpateluk.llmrouter.error.InvalidConfigException;
import io.github.manishpateluk.llmrouter.model.AttemptOutcome;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.provider.Provider;
import io.github.manishpateluk.llmrouter.provider.compatible.ProviderHttpException;

import org.junit.jupiter.api.Test;

/** {@code RouterConfig.maxRetries} and {@code retryDelay} (§5.5), against mocked providers. */
class LlmRouterRetriesTest {

    private static final Duration FAST = Duration.ofMillis(10);

    private final ScriptedAdapter anthropic = new ScriptedAdapter(Provider.ANTHROPIC);
    private final ScriptedAdapter openai = new ScriptedAdapter(Provider.OPENAI);
    private final LlmRouter router = new LlmRouter(List.of(anthropic, openai));

    private static RouterConfig.RouterConfigBuilder twoCandidates() {
        return RouterConfig.builder()
                .route(List.of(RouteEntry.of(Provider.ANTHROPIC, "model-a"), RouteEntry.of(Provider.OPENAI, "model-b")))
                .retryDelay(FAST);
    }

    private static Request request(RouterConfig config) {
        return Request.builder().prompt("Hello").config(config).build();
    }

    private static ProviderHttpException http(int status) {
        return new ProviderHttpException(status, "error " + status);
    }

    @Test
    void withoutMaxRetriesATransientFailureFallsBackStraightAway() {
        anthropic.script("model-a", fail(http(503)));

        Response response = router.complete(request(twoCandidates().build()));

        assertThat(anthropic.callsTo("model-a")).isEqualTo(1);
        assertThat(response.getModelUsed()).isEqualTo("model-b");
        assertThat(response.getAttempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.getOutcome()).isEqualTo(AttemptOutcome.FAILED);
            assertThat(attempt.getRetries()).isZero();
        });
    }

    @Test
    void aTransientFailureIsRetriedOnTheSameModel() {
        anthropic.script("model-a", fail(http(503)), fail(http(429)), ok());

        Response response = router.complete(request(twoCandidates().maxRetries(2).build()));

        assertThat(response.getModelUsed()).isEqualTo("model-a");
        assertThat(response.getAttempts()).isEmpty();
        assertThat(anthropic.callsTo("model-a")).isEqualTo(3);
        assertThat(openai.calls()).isEmpty();
    }

    @Test
    void afterMaxRetriesItFallsBackAndRecordsTheRetries() {
        anthropic.script("model-a", fail(http(529)));

        Response response = router.complete(request(twoCandidates().maxRetries(2).build()));

        assertThat(anthropic.callsTo("model-a")).isEqualTo(3);
        assertThat(response.getModelUsed()).isEqualTo("model-b");
        assertThat(response.getAttempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.getReason()).isEqualTo("HTTP 529: error 529");
            assertThat(attempt.getRetries()).isEqualTo(2);
        });
    }

    @Test
    void aFailureThatIsNotTransientIsNotRetried() {
        anthropic.script("model-a", fail(http(400)));

        Response response = router.complete(request(twoCandidates().maxRetries(3).build()));

        assertThat(anthropic.callsTo("model-a")).isEqualTo(1);
        assertThat(response.getModelUsed()).isEqualTo("model-b");
    }

    @Test
    void anUnclassifiedExceptionIsNotRetried() {
        anthropic.script("model-a", fail(new IllegalStateException("bug")));

        router.complete(request(twoCandidates().maxRetries(3).build()));

        assertThat(anthropic.callsTo("model-a")).isEqualTo(1);
    }

    @Test
    void aDroppedConnectionIsRetried() {
        anthropic.script("model-a", fail(new UncheckedIOException(new IOException("connection reset"))), ok());

        Response response = router.complete(request(twoCandidates().maxRetries(1).build()));

        assertThat(response.getModelUsed()).isEqualTo("model-a");
        assertThat(anthropic.callsTo("model-a")).isEqualTo(2);
    }

    @Test
    void retryDelayDoublesForEachRetry() {
        anthropic.script("model-a", fail(http(503)), fail(http(503)), ok());

        router.complete(request(twoCandidates().maxRetries(2).retryDelay(Duration.ofMillis(100)).build()));

        List<ScriptedAdapter.Call> calls = anthropic.calls();
        assertThat(gap(calls.get(0), calls.get(1))).isBetween(Duration.ofMillis(90), Duration.ofMillis(190));
        assertThat(gap(calls.get(1), calls.get(2))).isBetween(Duration.ofMillis(190), Duration.ofMillis(390));
    }

    @Test
    void aProvidersRetryAfterReplacesRetryDelay() {
        anthropic.script("model-a", fail(new ProviderHttpException(429, "slow down", Duration.ofMillis(300))), ok());

        router.complete(request(twoCandidates().maxRetries(1).build()));

        List<ScriptedAdapter.Call> calls = anthropic.calls();
        assertThat(gap(calls.get(0), calls.get(1))).isGreaterThanOrEqualTo(Duration.ofMillis(290));
    }

    @Test
    void aRetryAfterLongerThanTheRouterWaitsForFallsBackInstead() {
        anthropic.script("model-a", fail(new ProviderHttpException(429, "come back later", Duration.ofMinutes(5))));
        long start = System.nanoTime();

        Response response = router.complete(request(twoCandidates().maxRetries(2).build()));

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
        assertThat(anthropic.callsTo("model-a")).isEqualTo(1);
        assertThat(response.getModelUsed()).isEqualTo("model-b");
    }

    @Test
    void noRetryWhenTheWaitWouldRunPastCallTimeout() {
        anthropic.script("model-a", fail(http(503)));

        Response response = router.complete(request(twoCandidates()
                .maxRetries(2)
                .retryDelay(Duration.ofSeconds(5))
                .callTimeout(Duration.ofSeconds(2))
                .build()));

        assertThat(anthropic.callsTo("model-a")).isEqualTo(1);
        assertThat(response.getModelUsed()).isEqualTo("model-b");
    }

    @Test
    void retriesWorkOnTheAsyncPath() throws Exception {
        anthropic.script("model-a", fail(http(503)), ok());
        openai.script("model-b", fail(http(500)));
        RouterConfig config = RouterConfig.builder()
                .route(List.of(RouteEntry.of(Provider.OPENAI, "model-b"), RouteEntry.of(Provider.ANTHROPIC, "model-a")))
                .maxRetries(1)
                .retryDelay(FAST)
                .build();

        Response response = router.completeAsync(request(config)).get(5, TimeUnit.SECONDS);

        assertThat(response.getModelUsed()).isEqualTo("model-a");
        assertThat(openai.callsTo("model-b")).isEqualTo(2);
        assertThat(anthropic.callsTo("model-a")).isEqualTo(2);
        assertThat(response.getAttempts()).singleElement().satisfies(attempt -> assertThat(attempt.getRetries()).isEqualTo(1));
    }

    @Test
    void aStreamingRetryResetsTheListenerFirst() {
        anthropic.script("model-a", failAfter("partial ", http(503)), ok());
        List<String> events = new CopyOnWriteArrayList<>();
        StreamListener listener = new StreamListener() {
            @Override
            public void onText(String delta) {
                events.add(delta);
            }

            @Override
            public void onReset() {
                events.add("<reset>");
            }
        };

        Response response = router.completeStreaming(request(twoCandidates().maxRetries(1).build()), listener);

        assertThat(response.getModelUsed()).isEqualTo("model-a");
        assertThat(events).containsExactly("partial ", "<reset>", "ok from model-a");
    }

    @Test
    void aListenerFailureIsNeverRetried() {
        IllegalStateException broken = new IllegalStateException("listener broke");

        assertThatThrownBy(() -> router.completeStreaming(request(twoCandidates().maxRetries(3).build()), delta -> {
            throw broken;
        })).isSameAs(broken);

        assertThat(anthropic.callsTo("model-a")).isEqualTo(1);
    }

    @Test
    void negativeMaxRetriesOrANullRetryDelayIsRejected() {
        assertThatThrownBy(() -> router.complete(request(twoCandidates().maxRetries(-1).build())))
                .isInstanceOf(InvalidConfigException.class)
                .hasMessageContaining("maxRetries");
        assertThatThrownBy(() -> router.complete(request(twoCandidates().retryDelay(null).build())))
                .isInstanceOf(InvalidConfigException.class)
                .hasMessageContaining("retryDelay");
    }

    private static Duration gap(ScriptedAdapter.Call first, ScriptedAdapter.Call second) {
        return Duration.ofNanos(second.atNanos() - first.atNanos());
    }
}
