package io.github.manishpateluk.llmrouter;

import io.github.manishpateluk.llmrouter.config.RouteEntry;
import io.github.manishpateluk.llmrouter.config.RouterConfig;
import io.github.manishpateluk.llmrouter.error.RouterExhaustedException;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.model.Usage;
import io.github.manishpateluk.llmrouter.provider.Provider;
import io.github.manishpateluk.llmrouter.provider.ProviderAdapter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmRouterStreamingTest {

    private static final RouterConfig ANTHROPIC_THEN_OPENAI = RouterConfig.builder()
            .route(List.of(RouteEntry.of(Provider.ANTHROPIC, "claude-opus-5"), RouteEntry.of(Provider.OPENAI, "gpt-6-astra")))
            .build();

    /** Records what a stream delivered, including resets. */
    private static final class Recorder implements StreamListener {
        final List<String> events = new ArrayList<>();

        @Override
        public void onText(String delta) {
            events.add(delta);
        }

        @Override
        public void onReset() {
            events.add("<reset>");
        }
    }

    @Test
    void anAdapterWithoutNativeStreamingDeliversItsTextInOnePiece() {
        Recorder recorder = new Recorder();
        LlmRouter router = new LlmRouter(List.of(adapter(Provider.ANTHROPIC, "Hello there", null)));

        Response response = router.completeStreaming(request(), recorder);

        assertThat(recorder.events).containsExactly("Hello there");
        assertThat(response.getContent()).isEqualTo("Hello there");
        assertThat(response.getProviderUsed()).isEqualTo(Provider.ANTHROPIC);
    }

    @Test
    void aStreamingAdapterDeliversDeltasAndTheFullResponseIsStillReturned() {
        Recorder recorder = new Recorder();
        LlmRouter router = new LlmRouter(List.of(streamingAdapter(Provider.ANTHROPIC, List.of("Hel", "lo ", "there"), null)));

        Response response = router.completeStreaming(request(), recorder);

        assertThat(recorder.events).containsExactly("Hel", "lo ", "there");
        assertThat(response.getContent()).isEqualTo("Hello there");
        assertThat(response.getUsage().getInputTokens()).isEqualTo(3);
    }

    @Test
    void aFailureMidStreamResetsThenFallsBackToTheNextCandidate() {
        Recorder recorder = new Recorder();
        LlmRouter router = new LlmRouter(List.of(
                streamingAdapter(Provider.ANTHROPIC, List.of("Partial"), new RuntimeException("connection dropped")),
                streamingAdapter(Provider.OPENAI, List.of("Recovered ", "answer"), null)));

        Response response = router.completeStreaming(request(), recorder);

        assertThat(recorder.events).containsExactly("Partial", "<reset>", "Recovered ", "answer");
        assertThat(response.getContent()).isEqualTo("Recovered answer");
        assertThat(response.getProviderUsed()).isEqualTo(Provider.OPENAI);
        assertThat(response.getAttempts()).hasSize(1);
    }

    @Test
    void aFailureBeforeAnyTextFallsBackWithoutAReset() {
        Recorder recorder = new Recorder();
        LlmRouter router = new LlmRouter(List.of(
                streamingAdapter(Provider.ANTHROPIC, List.of(), new RuntimeException("rate limited")),
                adapter(Provider.OPENAI, "fine", null)));

        router.completeStreaming(request(), recorder);

        assertThat(recorder.events).containsExactly("fine");
    }

    @Test
    void exhaustionStillThrows() {
        LlmRouter router = new LlmRouter(List.of(adapter(Provider.ANTHROPIC, null, new RuntimeException("down"))));

        assertThatThrownBy(() -> router.completeStreaming(Request.builder().prompt("hi")
                .config(RouterConfig.builder().route(List.of(RouteEntry.of(Provider.ANTHROPIC, "claude-opus-5"))).build())
                .build(), delta -> { }))
                .isInstanceOf(RouterExhaustedException.class);
    }

    @Test
    void theRequestInterceptorAppliesToStreamingCallsToo() {
        List<String> seen = new ArrayList<>();
        LlmRouter router = new LlmRouter(List.of(adapter(Provider.ANTHROPIC, "ok", null)), (provider, model, request) -> {
            seen.add(provider + "/" + model);
            return request;
        });

        router.completeStreaming(request(), delta -> { });

        assertThat(seen).containsExactly("anthropic/claude-opus-5");
    }

    @Test
    void emptyTextIsNeverDeliveredAndNullListenersAreRejected() {
        Recorder recorder = new Recorder();
        LlmRouter router = new LlmRouter(List.of(adapter(Provider.ANTHROPIC, "", null)));

        router.completeStreaming(request(), recorder);

        assertThat(recorder.events).isEmpty();
        assertThatThrownBy(() -> router.completeStreaming(request(), null)).isInstanceOf(NullPointerException.class);
    }

    private static Request request() {
        return Request.builder().prompt("hi").config(ANTHROPIC_THEN_OPENAI).build();
    }

    private static Response fragment(String content) {
        return Response.builder().content(content).usage(Usage.builder().inputTokens(3).outputTokens(2).build()).build();
    }

    /** An adapter with only {@code send}: streaming falls back to the interface default. */
    private static ProviderAdapter adapter(Provider id, String content, RuntimeException failure) {
        return new ProviderAdapter() {
            @Override
            public Provider id() {
                return id;
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public Response send(String model, Request adaptedRequest) {
                if (failure != null) {
                    throw failure;
                }
                return fragment(content);
            }
        };
    }

    /** An adapter that streams {@code deltas}, then either completes or throws {@code failure}. */
    private static ProviderAdapter streamingAdapter(Provider id, List<String> deltas, RuntimeException failure) {
        return new ProviderAdapter() {
            @Override
            public Provider id() {
                return id;
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public Response send(String model, Request adaptedRequest) {
                throw new AssertionError("streaming calls must use sendStreaming");
            }

            @Override
            public Response sendStreaming(String model, Request adaptedRequest, Consumer<String> onText) {
                deltas.forEach(onText);
                if (failure != null) {
                    throw failure;
                }
                return fragment(String.join("", deltas));
            }
        };
    }
}
