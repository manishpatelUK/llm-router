package io.github.manishpateluk.llmrouter;

import static io.github.manishpateluk.llmrouter.ScriptedAdapter.fail;
import static io.github.manishpateluk.llmrouter.ScriptedAdapter.slow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import io.github.manishpateluk.llmrouter.config.RouteEntry;
import io.github.manishpateluk.llmrouter.config.RouterConfig;
import io.github.manishpateluk.llmrouter.error.InvalidConfigException;
import io.github.manishpateluk.llmrouter.error.RouterExhaustedException;
import io.github.manishpateluk.llmrouter.model.AttemptOutcome;
import io.github.manishpateluk.llmrouter.model.AttemptRecord;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.provider.Provider;
import io.github.manishpateluk.llmrouter.provider.compatible.ProviderHttpException;

import org.junit.jupiter.api.Test;

/** {@code RouterConfig.attemptTimeout} and {@code callTimeout} (§5.5), against mocked providers. */
class LlmRouterTimeoutsTest {

    private static final Duration SLOW = Duration.ofSeconds(3);

    private final ScriptedAdapter anthropic = new ScriptedAdapter(Provider.ANTHROPIC);
    private final ScriptedAdapter openai = new ScriptedAdapter(Provider.OPENAI);
    private final LlmRouter router = new LlmRouter(List.of(anthropic, openai));

    private static RouterConfig.RouterConfigBuilder twoCandidates() {
        return RouterConfig.builder().route(List.of(
                RouteEntry.of(Provider.ANTHROPIC, "slow-model"),
                RouteEntry.of(Provider.OPENAI, "fast-model")));
    }

    private static Request request(RouterConfig config) {
        return Request.builder().prompt("Hello").config(config).build();
    }

    /** Whether {@code condition} becomes true within two seconds. */
    private static boolean eventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(10);
        }
        return true;
    }

    @Test
    void withoutATimeoutASlowAttemptRunsToTheEnd() {
        anthropic.script("slow-model", slow(Duration.ofMillis(300)));

        Response response = router.complete(request(twoCandidates().build()));

        assertThat(response.getModelUsed()).isEqualTo("slow-model");
        assertThat(anthropic.wasInterrupted()).isFalse();
    }

    @Test
    void anAttemptThatOutlastsAttemptTimeoutFailsAndFallsBack() throws Exception {
        anthropic.script("slow-model", slow(SLOW));
        long start = System.nanoTime();

        Response response = router.complete(request(twoCandidates().attemptTimeout(Duration.ofMillis(200)).build()));

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1500));
        assertThat(response.getModelUsed()).isEqualTo("fast-model");
        assertThat(response.getAttempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.getOutcome()).isEqualTo(AttemptOutcome.FAILED);
            assertThat(attempt.getReason()).isEqualTo("attempt timed out after 200ms");
        });
        assertThat(eventually(anthropic::wasInterrupted)).isTrue();
    }

    @Test
    void anAttemptWithinAttemptTimeoutSucceedsAsUsual() {
        anthropic.script("slow-model", slow(Duration.ofMillis(50)));

        Response response = router.complete(request(twoCandidates().attemptTimeout(Duration.ofSeconds(5)).build()));

        assertThat(response.getModelUsed()).isEqualTo("slow-model");
        assertThat(response.getAttempts()).isEmpty();
    }

    @Test
    void aProviderFailureWithinAttemptTimeoutIsReportedAsItself() {
        anthropic.script("slow-model", fail(new ProviderHttpException(400, "bad request")));

        Response response = router.complete(request(twoCandidates().attemptTimeout(Duration.ofSeconds(5)).build()));

        assertThat(response.getAttempts().get(0).getReason()).isEqualTo("HTTP 400: bad request");
    }

    @Test
    void attemptTimeoutAppliesOnTheAsyncPath() throws Exception {
        anthropic.script("slow-model", slow(SLOW));

        Response response = router.completeAsync(request(twoCandidates().attemptTimeout(Duration.ofMillis(200)).build()))
                .get(2, TimeUnit.SECONDS);

        assertThat(response.getModelUsed()).isEqualTo("fast-model");
        assertThat(response.getAttempts().get(0).getReason()).isEqualTo("attempt timed out after 200ms");
    }

    @Test
    void aTimedOutStreamIsResetAndItsLateTextNeverReachesTheListener() throws Exception {
        anthropic.script("slow-model", new ScriptedAdapter.Slow(SLOW, "partial ", "late "));
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

        Response response = router.completeStreaming(request(twoCandidates().attemptTimeout(Duration.ofMillis(200)).build()), listener);

        assertThat(response.getContent()).isEqualTo("ok from fast-model");
        assertThat(eventually(anthropic::wasInterrupted)).isTrue();
        assertThat(events).containsExactly("partial ", "<reset>", "ok from fast-model");
    }

    @Test
    void aTimedOutAsyncStreamIsResetToo() throws Exception {
        anthropic.script("slow-model", new ScriptedAdapter.Slow(Duration.ofMillis(800), "partial ", "late "));
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

        Response response = router.completeStreamingAsync(request(twoCandidates().attemptTimeout(Duration.ofMillis(200)).build()), listener)
                .get(3, TimeUnit.SECONDS);
        Thread.sleep(800); // let the abandoned attempt try to stream its late text

        assertThat(response.getContent()).isEqualTo("ok from fast-model");
        assertThat(events).containsExactly("partial ", "<reset>", "ok from fast-model");
    }

    @Test
    void callTimeoutStopsTheCallWithoutTryingFurtherCandidates() {
        anthropic.script("slow-model", slow(SLOW));
        long start = System.nanoTime();

        assertThatThrownBy(() -> router.complete(request(twoCandidates().callTimeout(Duration.ofMillis(300)).build())))
                .isInstanceOfSatisfying(RouterExhaustedException.class, e -> assertThat(e.attempts())
                        .extracting(AttemptRecord::getReason)
                        .containsExactly("call timed out: callTimeout of 300ms reached"));

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1500));
        assertThat(openai.calls()).isEmpty();
    }

    @Test
    void callTimeoutBoundsTheWholeCallAcrossFallbacks() {
        anthropic.script("slow-model", slow(Duration.ofMillis(250)));
        openai.script("fast-model", slow(SLOW));
        RouterConfig config = twoCandidates()
                .attemptTimeout(Duration.ofMillis(200))
                .callTimeout(Duration.ofMillis(350))
                .build();

        assertThatThrownBy(() -> router.complete(request(config)))
                .isInstanceOfSatisfying(RouterExhaustedException.class, e -> assertThat(e.attempts())
                        .extracting(AttemptRecord::getReason)
                        .containsExactly("attempt timed out after 200ms", "call timed out: callTimeout of 350ms reached"));
    }

    @Test
    void callTimeoutAppliesOnTheAsyncPath() {
        anthropic.script("slow-model", slow(SLOW));
        long start = System.nanoTime();

        assertThatThrownBy(() -> router.completeAsync(request(twoCandidates().callTimeout(Duration.ofMillis(300)).build()))
                        .get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOfSatisfying(RouterExhaustedException.class, e -> assertThat(e.attempts())
                        .extracting(AttemptRecord::getReason)
                        .containsExactly("call timed out: callTimeout of 300ms reached"));

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1500));
        assertThat(openai.calls()).isEmpty();
    }

    @Test
    void aTimedOutAttemptIsNotRetried() {
        anthropic.script("slow-model", slow(SLOW));

        Response response = router.complete(request(twoCandidates()
                .attemptTimeout(Duration.ofMillis(150))
                .maxRetries(3)
                .retryDelay(Duration.ofMillis(10))
                .build()));

        assertThat(anthropic.callsTo("slow-model")).isEqualTo(1);
        assertThat(response.getAttempts().get(0).getRetries()).isZero();
    }

    @Test
    void nonPositiveTimeoutsAreRejected() {
        assertThatThrownBy(() -> router.complete(request(twoCandidates().attemptTimeout(Duration.ZERO).build())))
                .isInstanceOf(InvalidConfigException.class)
                .hasMessageContaining("attemptTimeout");
        assertThatThrownBy(() -> router.complete(request(twoCandidates().callTimeout(Duration.ofSeconds(-1)).build())))
                .isInstanceOf(InvalidConfigException.class)
                .hasMessageContaining("callTimeout");
        assertThat(anthropic.calls()).isEmpty();
    }
}
