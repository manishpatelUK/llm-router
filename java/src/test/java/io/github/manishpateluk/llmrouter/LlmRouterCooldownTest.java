package io.github.manishpateluk.llmrouter;

import static io.github.manishpateluk.llmrouter.ScriptedAdapter.fail;
import static io.github.manishpateluk.llmrouter.ScriptedAdapter.ok;
import static io.github.manishpateluk.llmrouter.ScriptedAdapter.slow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.github.manishpateluk.llmrouter.config.CooldownPolicy;
import io.github.manishpateluk.llmrouter.config.RouteEntry;
import io.github.manishpateluk.llmrouter.config.RouterConfig;
import io.github.manishpateluk.llmrouter.error.InvalidConfigException;
import io.github.manishpateluk.llmrouter.error.RouterExhaustedException;
import io.github.manishpateluk.llmrouter.model.AttemptOutcome;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.provider.Provider;
import io.github.manishpateluk.llmrouter.provider.compatible.ProviderHttpException;

import org.junit.jupiter.api.Test;

/** {@code RouterConfig.cooldown} (§5.5), against mocked providers and a clock the test moves. */
class LlmRouterCooldownTest {

    /** A clock that only moves when told to. */
    private static final class TestClock extends Clock {

        private volatile Instant now = Instant.parse("2026-10-20T12:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final CooldownPolicy TWO_FAILURES_ONE_MINUTE = CooldownPolicy.of(2, Duration.ofMinutes(1));

    private final TestClock clock = new TestClock();
    private final ScriptedAdapter anthropic = new ScriptedAdapter(Provider.ANTHROPIC);
    private final ScriptedAdapter openai = new ScriptedAdapter(Provider.OPENAI);
    private final LlmRouter router = new LlmRouter(List.of(anthropic, openai), (provider, model, request) -> request, clock);

    private static RouterConfig.RouterConfigBuilder twoCandidates() {
        return RouterConfig.builder()
                .route(List.of(RouteEntry.of(Provider.ANTHROPIC, "model-a"), RouteEntry.of(Provider.OPENAI, "model-b")));
    }

    private static Request request(RouterConfig config) {
        return Request.builder().prompt("Hello").config(config).build();
    }

    private static ProviderHttpException down() {
        return new ProviderHttpException(503, "down");
    }

    @Test
    void withoutCooldownAFailingModelIsTriedOnEveryCall() {
        anthropic.script("model-a", fail(down()));

        for (int i = 0; i < 5; i++) {
            router.complete(request(twoCandidates().build()));
        }

        assertThat(anthropic.callsTo("model-a")).isEqualTo(5);
    }

    @Test
    void afterEnoughFailuresInARowTheModelIsSkippedOnLaterCalls() {
        anthropic.script("model-a", fail(down()));
        RouterConfig config = twoCandidates().cooldown(TWO_FAILURES_ONE_MINUTE).build();

        router.complete(request(config));
        router.complete(request(config));
        Response third = router.complete(request(config));

        assertThat(anthropic.callsTo("model-a")).isEqualTo(2);
        assertThat(third.getModelUsed()).isEqualTo("model-b");
        assertThat(third.getAttempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.getOutcome()).isEqualTo(AttemptOutcome.SKIPPED);
            assertThat(attempt.getReason()).isEqualTo("cooling down for another 60s after 2 failed attempt(s) in a row");
        });
    }

    @Test
    void whenTheCooldownEndsTheModelIsTriedAgainWithItsCountReset() {
        anthropic.script("model-a", fail(down()));
        RouterConfig config = twoCandidates().cooldown(TWO_FAILURES_ONE_MINUTE).build();
        router.complete(request(config));
        router.complete(request(config));

        clock.advance(Duration.ofSeconds(30));
        router.complete(request(config));
        assertThat(anthropic.callsTo("model-a")).as("still cooling down").isEqualTo(2);

        clock.advance(Duration.ofSeconds(30));
        router.complete(request(config));
        assertThat(anthropic.callsTo("model-a")).as("tried again once the minute is up").isEqualTo(3);

        router.complete(request(config));
        assertThat(anthropic.callsTo("model-a")).as("one failure since the reset isn't enough").isEqualTo(4);

        router.complete(request(config));
        assertThat(anthropic.callsTo("model-a")).as("two failures since the reset start a new cooldown").isEqualTo(4);
    }

    @Test
    void aSuccessEndsTheRunOfFailures() {
        anthropic.script("model-a", fail(down()), ok(), fail(down()));
        RouterConfig config = twoCandidates().cooldown(TWO_FAILURES_ONE_MINUTE).build();

        router.complete(request(config));
        router.complete(request(config));
        router.complete(request(config));
        router.complete(request(config));

        assertThat(anthropic.callsTo("model-a")).isEqualTo(4);
    }

    @Test
    void whenEveryCandidateIsCoolingDownTheCallIsExhausted() {
        anthropic.script("model-a", fail(down()));
        RouterConfig config = RouterConfig.builder()
                .route(List.of(RouteEntry.of(Provider.ANTHROPIC, "model-a")))
                .cooldown(CooldownPolicy.of(1, Duration.ofMinutes(1)))
                .build();
        assertThatThrownBy(() -> router.complete(request(config))).isInstanceOf(RouterExhaustedException.class);

        assertThatThrownBy(() -> router.complete(request(config)))
                .isInstanceOfSatisfying(RouterExhaustedException.class, e -> assertThat(e.attempts())
                        .singleElement()
                        .satisfies(attempt -> {
                            assertThat(attempt.getOutcome()).isEqualTo(AttemptOutcome.SKIPPED);
                            assertThat(attempt.getReason()).startsWith("cooling down");
                        }));
        assertThat(anthropic.callsTo("model-a")).isEqualTo(1);
    }

    @Test
    void aCallWithCooldownOffIgnoresTheHistory() {
        anthropic.script("model-a", fail(down()));
        RouterConfig withCooldown = twoCandidates().cooldown(CooldownPolicy.of(1, Duration.ofMinutes(1))).build();
        router.complete(request(withCooldown));

        router.complete(request(twoCandidates().build()));

        assertThat(anthropic.callsTo("model-a")).isEqualTo(2);
    }

    @Test
    void anAttemptsRetriesCountAsOneFailure() {
        anthropic.script("model-a", fail(down()));
        RouterConfig config = twoCandidates()
                .cooldown(TWO_FAILURES_ONE_MINUTE)
                .maxRetries(2)
                .retryDelay(Duration.ofMillis(5))
                .build();

        router.complete(request(config));
        assertThat(anthropic.callsTo("model-a")).isEqualTo(3);

        router.complete(request(config));
        assertThat(anthropic.callsTo("model-a")).as("second call still tries it").isEqualTo(6);

        router.complete(request(config));
        assertThat(anthropic.callsTo("model-a")).as("cooling down after two failed attempts").isEqualTo(6);
    }

    @Test
    void aTimeoutCountsAsAFailure() {
        anthropic.script("model-a", slow(Duration.ofSeconds(3)));
        RouterConfig config = twoCandidates()
                .cooldown(CooldownPolicy.of(1, Duration.ofMinutes(1)))
                .attemptTimeout(Duration.ofMillis(100))
                .build();

        router.complete(request(config));
        Response second = router.complete(request(config));

        assertThat(anthropic.callsTo("model-a")).isEqualTo(1);
        assertThat(second.getAttempts().get(0).getReason()).startsWith("cooling down");
    }

    @Test
    void anInterceptorFailureDoesNotCount() {
        LlmRouter intercepted = new LlmRouter(List.of(anthropic, openai), (provider, model, request) -> {
            if (provider == Provider.ANTHROPIC) {
                throw new IllegalStateException("interceptor broke");
            }
            return request;
        }, clock);
        RouterConfig config = twoCandidates().cooldown(CooldownPolicy.of(1, Duration.ofMinutes(1))).build();

        intercepted.complete(request(config));
        Response second = intercepted.complete(request(config));

        assertThat(second.getAttempts().get(0).getReason()).isEqualTo("interceptor broke");
    }

    @Test
    void aProviderOnlyEntryPicksAnotherModelWhileOneCoolsDown() {
        RouterConfig config = RouterConfig.builder()
                .route(List.of(RouteEntry.of(Provider.ANTHROPIC)))
                .cooldown(CooldownPolicy.of(1, Duration.ofMinutes(1)))
                .build();
        String first = router.complete(request(config)).getModelUsed();
        anthropic.script(first, fail(down()));
        assertThatThrownBy(() -> router.complete(request(config))).isInstanceOf(RouterExhaustedException.class);

        Response response = router.complete(request(config));

        assertThat(response.getModelUsed()).isNotEqualTo(first);
        assertThat(anthropic.callsTo(first)).isEqualTo(2);
    }

    @Test
    void cooldownWorksOnTheAsyncPath() throws Exception {
        anthropic.script("model-a", fail(down()));
        RouterConfig config = twoCandidates().cooldown(CooldownPolicy.of(1, Duration.ofMinutes(1))).build();

        router.completeAsync(request(config)).get(5, TimeUnit.SECONDS);
        Response second = router.completeAsync(request(config)).get(5, TimeUnit.SECONDS);

        assertThat(anthropic.callsTo("model-a")).isEqualTo(1);
        assertThat(second.getAttempts().get(0).getReason()).startsWith("cooling down");
    }

    @Test
    void anInvalidCooldownPolicyIsRejected() {
        assertThatThrownBy(() -> router.complete(request(twoCandidates().cooldown(CooldownPolicy.of(0, Duration.ofMinutes(1))).build())))
                .isInstanceOf(InvalidConfigException.class)
                .hasMessageContaining("cooldown.failures");
        assertThatThrownBy(() -> router.complete(request(twoCandidates().cooldown(CooldownPolicy.of(1, Duration.ZERO)).build())))
                .isInstanceOf(InvalidConfigException.class)
                .hasMessageContaining("cooldown.duration");
        assertThatThrownBy(() -> router.complete(request(twoCandidates().cooldown(CooldownPolicy.of(1, null)).build())))
                .isInstanceOf(InvalidConfigException.class)
                .hasMessageContaining("cooldown.duration");
    }
}
