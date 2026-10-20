package io.github.manishpateluk.llmrouter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.github.manishpateluk.llmrouter.config.RouteEntry;
import io.github.manishpateluk.llmrouter.config.RouterConfig;
import io.github.manishpateluk.llmrouter.config.ThinkingLevel;
import io.github.manishpateluk.llmrouter.model.Message;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.provider.Provider;

import org.junit.jupiter.api.Test;

/**
 * Every public {@code complete}/{@code completeAsync}/{@code completeStreaming} signature, called
 * as before timeouts, retries and cooldown existed: each still reaches the adapter on the
 * caller's thread (sync) with the request it describes, and returns its answer.
 */
class LlmRouterCallSurfaceTest {

    private static final RouterConfig CONFIG = RouterConfig.builder()
            .route(List.of(RouteEntry.of(Provider.ANTHROPIC, "model-a")))
            .thinkingLevel(ThinkingLevel.HIGH)
            .build();

    private final ScriptedAdapter anthropic = new ScriptedAdapter(Provider.ANTHROPIC);
    private final LlmRouter router = new LlmRouter(List.of(anthropic));
    private final List<Message> history = List.of(Message.user("What's 2+2?"), Message.assistant("4."));

    @Test
    void defaultConfigSetsNoTimeoutsRetriesOrCooldown() {
        RouterConfig config = RouterConfig.builder().build();

        assertThat(config.getAttemptTimeout()).isNull();
        assertThat(config.getCallTimeout()).isNull();
        assertThat(config.getMaxRetries()).isZero();
        assertThat(config.getRetryDelay()).isEqualTo(Duration.ofSeconds(1));
        assertThat(config.getCooldown()).isNull();
        assertThat(config.isPromptCaching()).isTrue();
    }

    @Test
    void completeWithPromptOnlyUsesTheDefaultRouteOnTheCallersThread() {
        Response response = router.complete("Hello");

        assertThat(response.getProviderUsed()).isEqualTo(Provider.ANTHROPIC);
        assertThat(anthropic.calls()).singleElement().satisfies(call -> {
            assertThat(call.request().getPrompt()).isEqualTo("Hello");
            assertThat(call.thread()).isSameAs(Thread.currentThread());
        });
    }

    @Test
    void completeWithConfig() {
        Response response = router.complete("Hello", CONFIG);

        assertThat(response.getModelUsed()).isEqualTo("model-a");
        assertThat(response.getThinkingLevel()).isEqualTo(ThinkingLevel.HIGH);
        assertThat(anthropic.calls().get(0).thread()).isSameAs(Thread.currentThread());
    }

    @Test
    void completeWithHistory() {
        router.complete("And 2+3?", history);

        assertThat(anthropic.calls().get(0).request().getHistory()).isEqualTo(history);
    }

    @Test
    void completeWithHistoryAndConfig() {
        Response response = router.complete("And 2+3?", history, CONFIG);

        assertThat(response.getModelUsed()).isEqualTo("model-a");
        assertThat(anthropic.calls().get(0).request().getHistory()).isEqualTo(history);
    }

    @Test
    void completeWithSystemInstructions() {
        router.complete("Summarize", "Be terse.");

        assertThat(anthropic.calls().get(0).request().getSystemInstructions()).isEqualTo("Be terse.");
    }

    @Test
    void completeWithSystemInstructionsAndConfig() {
        Response response = router.complete("Summarize", "Be terse.", CONFIG);

        assertThat(response.getModelUsed()).isEqualTo("model-a");
        assertThat(anthropic.calls().get(0).request().getSystemInstructions()).isEqualTo("Be terse.");
    }

    @Test
    void completeWithRequest() {
        Response response = router.complete(Request.builder().prompt("Hello").config(CONFIG).build());

        assertThat(response.getContent()).isEqualTo("ok from model-a");
        assertThat(response.getAttempts()).isEmpty();
        assertThat(anthropic.calls().get(0).thread()).isSameAs(Thread.currentThread());
    }

    @Test
    void everyAsyncOverloadCompletes() throws Exception {
        List<CompletableFuture<Response>> futures = List.of(
                router.completeAsync("Hello"),
                router.completeAsync("Hello", CONFIG),
                router.completeAsync("And 2+3?", history),
                router.completeAsync("And 2+3?", history, CONFIG),
                router.completeAsync("Summarize", "Be terse."),
                router.completeAsync("Summarize", "Be terse.", CONFIG),
                router.completeAsync(Request.builder().prompt("Hello").config(CONFIG).build()));

        for (CompletableFuture<Response> future : futures) {
            assertThat(future.get(5, TimeUnit.SECONDS).getProviderUsed()).isEqualTo(Provider.ANTHROPIC);
        }
        assertThat(anthropic.calls()).hasSize(7);
    }

    @Test
    void callbackStyleAsyncDeliversTheResponse() throws Exception {
        CompletableFuture<Response> delivered = new CompletableFuture<>();

        router.completeAsync(Request.builder().prompt("Hello").config(CONFIG).build(),
                delivered::complete, delivered::completeExceptionally);

        assertThat(delivered.get(5, TimeUnit.SECONDS).getModelUsed()).isEqualTo("model-a");
    }

    @Test
    void streamingStillStreamsOnTheCallersThread() {
        StringBuilder streamed = new StringBuilder();
        AtomicReference<Thread> listenerThread = new AtomicReference<>();

        Response response = router.completeStreaming(Request.builder().prompt("Hello").config(CONFIG).build(), delta -> {
            listenerThread.set(Thread.currentThread());
            streamed.append(delta);
        });

        assertThat(streamed).hasToString("ok from model-a");
        assertThat(response.getContent()).isEqualTo("ok from model-a");
        assertThat(listenerThread.get()).isSameAs(Thread.currentThread());
    }

    @Test
    void asyncStreamingStillStreams() throws Exception {
        StringBuilder streamed = new StringBuilder();

        Response response = router.completeStreamingAsync(Request.builder().prompt("Hello").config(CONFIG).build(), streamed::append)
                .get(5, TimeUnit.SECONDS);

        assertThat(streamed).hasToString("ok from model-a");
        assertThat(response.getContent()).isEqualTo("ok from model-a");
    }
}
