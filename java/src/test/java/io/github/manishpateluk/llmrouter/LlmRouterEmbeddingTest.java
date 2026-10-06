package io.github.manishpateluk.llmrouter;

import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.sun.net.httpserver.HttpServer;
import io.github.manishpateluk.llmrouter.config.RouteEntry;
import io.github.manishpateluk.llmrouter.error.NoProvidersConfiguredException;
import io.github.manishpateluk.llmrouter.error.RouterExhaustedException;
import io.github.manishpateluk.llmrouter.model.EmbeddingRequest;
import io.github.manishpateluk.llmrouter.model.EmbeddingResponse;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.model.Usage;
import io.github.manishpateluk.llmrouter.provider.Provider;
import io.github.manishpateluk.llmrouter.provider.ProviderAdapter;
import io.github.manishpateluk.llmrouter.provider.openai.OpenAiAdapter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmRouterEmbeddingTest {

    @Test
    void withNoRouteTheFirstProviderOfferingEmbeddingsIsUsedWithItsDefaultModel() {
        List<String> calls = new ArrayList<>();
        LlmRouter router = new LlmRouter(List.of(chatOnly(Provider.ANTHROPIC), embedding(Provider.OPENAI, "embed-default", calls, null)));

        EmbeddingResponse response = router.embed(EmbeddingRequest.builder().text("hello").text("world").build());

        assertThat(response.getVectors()).hasSize(2);
        assertThat(response.getVectors().get(1)).containsExactly(1f, 5f);
        assertThat(response.getProviderUsed()).isEqualTo(Provider.OPENAI);
        assertThat(response.getModelUsed()).isEqualTo("embed-default");
        assertThat(response.getUsage().getInputTokens()).isEqualTo(4);
        assertThat(calls).containsExactly("embed-default:2");
    }

    @Test
    void anExplicitRouteIsTriedInOrderWithFallbackOnlyAcrossTheListedCandidates() {
        List<String> calls = new ArrayList<>();
        LlmRouter router = new LlmRouter(List.of(
                embedding(Provider.OPENAI, "embed-default", calls, new RuntimeException("rate limited")),
                embedding(Provider.OPENROUTER, "other-default", calls, null)));

        EmbeddingResponse response = router.embed(EmbeddingRequest.builder().text("x")
                .route(List.of(RouteEntry.of(Provider.OPENAI, "big-model"), RouteEntry.of(Provider.OPENROUTER)))
                .dimensions(256).build());

        assertThat(calls).containsExactly("big-model:1@256", "other-default:1@256");
        assertThat(response.getModelUsed()).isEqualTo("other-default");
        assertThat(response.getAttempts()).singleElement().satisfies(a -> assertThat(a.getModel()).isEqualTo("big-model"));
    }

    @Test
    void providersWithoutEmbeddingsAreSkippedAndExhaustionThrows() {
        LlmRouter router = new LlmRouter(List.of(chatOnly(Provider.ANTHROPIC)));

        assertThatThrownBy(() -> router.embed(EmbeddingRequest.builder().text("x").build()))
                .isInstanceOf(NoProvidersConfiguredException.class);
        assertThatThrownBy(() -> router.embed(EmbeddingRequest.builder().text("x").route(List.of(RouteEntry.of(Provider.ANTHROPIC))).build()))
                .isInstanceOf(RouterExhaustedException.class).hasMessageContaining("offers no embeddings");
        assertThatThrownBy(() -> router.embed(EmbeddingRequest.builder().build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theWrongNumberOfVectorsCountsAsAFailedAttempt() {
        ProviderAdapter broken = new ProviderAdapter() {
            public Provider id() {
                return Provider.OPENAI;
            }

            public boolean isAvailable() {
                return true;
            }

            public Response send(String model, Request request) {
                throw new UnsupportedOperationException();
            }

            public String defaultEmbeddingModel() {
                return "m";
            }

            public EmbeddingResponse embed(String model, List<String> texts, Integer dimensions) {
                return EmbeddingResponse.builder().vectors(List.of()).usage(Usage.builder().build()).build();
            }
        };

        assertThatThrownBy(() -> new LlmRouter(List.of(broken)).embed(EmbeddingRequest.builder().text("x").build()))
                .isInstanceOf(RouterExhaustedException.class).hasMessageContaining("expected 1 vectors");
    }

    @Test
    void theOpenAiAdapterCallsTheEmbeddingsEndpoint() throws IOException {
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] json = ("{\"object\":\"list\",\"model\":\"text-embedding-3-small\",\"data\":["
                    + "{\"object\":\"embedding\",\"index\":1,\"embedding\":[0.5,-0.25]},"
                    + "{\"object\":\"embedding\",\"index\":0,\"embedding\":[0.1,0.2]}],"
                    + "\"usage\":{\"prompt_tokens\":6,\"total_tokens\":6}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, json.length);
            exchange.getResponseBody().write(json);
            exchange.close();
        });
        server.start();
        try {
            OpenAiAdapter adapter = new OpenAiAdapter(OpenAIOkHttpClient.builder().apiKey("test")
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1").build());

            EmbeddingResponse response = new LlmRouter(List.of(adapter))
                    .embed(EmbeddingRequest.builder().text("first").text("second").dimensions(2).build());

            assertThat(adapter.defaultEmbeddingModel()).isEqualTo("text-embedding-3-small");
            assertThat(response.getVectors().get(0)).containsExactly(0.1f, 0.2f);
            assertThat(response.getVectors().get(1)).containsExactly(0.5f, -0.25f); // reordered by index
            assertThat(response.getUsage().getInputTokens()).isEqualTo(6);
            assertThat(body.get()).contains("\"model\":\"text-embedding-3-small\"", "\"input\":[\"first\",\"second\"]", "\"dimensions\":2");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void costIsEstimatedFromTheTablesEmbeddingPricesInCentsAndMicroDollars() {
        // text-embedding-3-small is $0.02 per million input tokens in the seed table
        LlmRouter large = new LlmRouter(List.of(tokens(Provider.OPENAI, "text-embedding-3-small", 2_000_000)));
        LlmRouter small = new LlmRouter(List.of(tokens(Provider.OPENAI, "text-embedding-3-small", 2_000)));

        Usage largeUsage = large.embed(EmbeddingRequest.builder().text("x").build()).getUsage();
        Usage smallUsage = small.embed(EmbeddingRequest.builder().text("x").build()).getUsage();

        assertThat(largeUsage.getEstimatedCostUsdMicros()).isEqualTo(40_000); // $0.04
        assertThat(largeUsage.getEstimatedCostUsdCents()).isEqualTo(4);
        assertThat(smallUsage.getEstimatedCostUsdMicros()).isEqualTo(40); // $0.00004: too small for whole cents
        assertThat(smallUsage.getEstimatedCostUsdCents()).isZero();
        assertThat(smallUsage.getInputTokens()).isEqualTo(2_000);
    }

    @Test
    void aModelWithNoEmbeddingPriceKeepsZeroCostButStillReportsTokens() {
        LlmRouter router = new LlmRouter(List.of(tokens(Provider.OPENAI, "not-in-the-table", 5_000)));

        Usage usage = router.embed(EmbeddingRequest.builder().text("x").build()).getUsage();

        assertThat(usage.getInputTokens()).isEqualTo(5_000);
        assertThat(usage.getEstimatedCostUsdMicros()).isZero();
        assertThat(usage.getEstimatedCostUsdCents()).isZero();
    }

    /** An embedding adapter whose default model reports a fixed number of input tokens per call. */
    private static ProviderAdapter tokens(Provider id, String defaultModel, int inputTokens) {
        return new ProviderAdapter() {
            public Provider id() {
                return id;
            }

            public boolean isAvailable() {
                return true;
            }

            public Response send(String model, Request request) {
                throw new UnsupportedOperationException();
            }

            public String defaultEmbeddingModel() {
                return defaultModel;
            }

            public EmbeddingResponse embed(String model, List<String> texts, Integer dimensions) {
                List<float[]> vectors = new ArrayList<>();
                texts.forEach(text -> vectors.add(new float[]{1f}));
                return EmbeddingResponse.builder().vectors(vectors).usage(Usage.builder().inputTokens(inputTokens).build()).build();
            }
        };
    }

    private static ProviderAdapter chatOnly(Provider id) {
        return new ProviderAdapter() {
            public Provider id() {
                return id;
            }

            public boolean isAvailable() {
                return true;
            }

            public Response send(String model, Request request) {
                return Response.builder().content("ok").usage(Usage.builder().build()).build();
            }
        };
    }

    private static ProviderAdapter embedding(Provider id, String defaultModel, List<String> calls, RuntimeException failure) {
        return new ProviderAdapter() {
            public Provider id() {
                return id;
            }

            public boolean isAvailable() {
                return true;
            }

            public Response send(String model, Request request) {
                throw new UnsupportedOperationException();
            }

            public String defaultEmbeddingModel() {
                return defaultModel;
            }

            public EmbeddingResponse embed(String model, List<String> texts, Integer dimensions) {
                calls.add(model + ":" + texts.size() + (dimensions == null ? "" : "@" + dimensions));
                if (failure != null) {
                    throw failure;
                }
                List<float[]> vectors = new ArrayList<>();
                for (int i = 0; i < texts.size(); i++) {
                    vectors.add(new float[]{i, texts.get(i).length()});
                }
                return EmbeddingResponse.builder().vectors(vectors)
                        .usage(Usage.builder().inputTokens(2 * texts.size()).build()).build();
            }
        };
    }
}
