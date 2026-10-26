package io.github.manishpateluk.llmrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import tools.jackson.databind.ObjectMapper;
import io.github.manishpateluk.llmrouter.capability.ModelCapabilityTable;
import io.github.manishpateluk.llmrouter.capability.ModelEntry;
import io.github.manishpateluk.llmrouter.config.Feature;
import io.github.manishpateluk.llmrouter.config.RouteEntry;
import io.github.manishpateluk.llmrouter.config.RouterConfig;
import io.github.manishpateluk.llmrouter.config.StructuredOutputStrategy;
import io.github.manishpateluk.llmrouter.error.InvalidConfigException;
import io.github.manishpateluk.llmrouter.error.RouterExhaustedException;
import io.github.manishpateluk.llmrouter.model.Attachment;
import io.github.manishpateluk.llmrouter.model.AttemptUsage;
import io.github.manishpateluk.llmrouter.model.CallUsage;
import io.github.manishpateluk.llmrouter.model.Message;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.model.Role;
import io.github.manishpateluk.llmrouter.model.Usage;
import io.github.manishpateluk.llmrouter.negotiation.CapabilityNegotiator;
import io.github.manishpateluk.llmrouter.negotiation.NegotiationResult;
import io.github.manishpateluk.llmrouter.provider.Provider;
import io.github.manishpateluk.llmrouter.provider.ProviderAdapter;
import io.github.manishpateluk.llmrouter.routing.RequestEstimator;

import org.junit.jupiter.api.Test;

/** Attachments on history messages (negotiated, validated, estimated) and the cache fields of {@code Usage} end to end. */
class HistoryAttachmentsAndCacheUsageTest {

    private static final Attachment PNG = TestMedia.pngAttachment();
    private static final Attachment PDF = TestMedia.deckPagePdf();

    private static ModelEntry imagesOnly(Integer maxImages) {
        return ModelEntry.builder().provider(Provider.OPENROUTER).model("m")
                .inputMediaTypes(List.of("image/png")).maxImagesPerRequest(maxImages).build();
    }

    private static NegotiationResult negotiate(ModelEntry model, Request request) {
        return CapabilityNegotiator.negotiate(model, request, StructuredOutputStrategy.AUTO);
    }

    @Test
    void historyAttachmentsAreNegotiatedLikeThePromptsWithPositionsAcrossBoth() {
        Request request = Request.builder()
                .history(List.of(Message.user("deck", List.of(PDF, PNG)), Message.assistant("seen")))
                .attachments(List.of(PDF, PNG))
                .prompt("p")
                .build();

        NegotiationResult result = negotiate(imagesOnly(null), request);

        assertThat(result.getDroppedFeatures()).contains("attachments");
        assertThat(result.getDroppedAttachments()).extracting(dropped -> dropped.getIndex()).containsExactly(0, 2);
        assertThat(result.getAdaptedRequest().getHistory().get(0).getAttachments()).containsExactly(PNG);
        assertThat(result.getAdaptedRequest().getHistory().get(0).getContent()).isEqualTo("deck");
        assertThat(result.getAdaptedRequest().getHistory().get(1)).isSameAs(request.getHistory().get(1));
        assertThat(result.getAdaptedRequest().getAttachments()).containsExactly(PNG);
    }

    @Test
    void theImageLimitCountsTheHistorysImagesFirst() {
        Request request = Request.builder()
                .history(List.of(Message.user("one", List.of(PNG)), Message.user("two", List.of(PNG))))
                .attachments(List.of(PNG))
                .prompt("p")
                .build();

        NegotiationResult result = negotiate(imagesOnly(2), request);

        assertThat(result.getAdaptedRequest().everyAttachment()).hasSize(2);
        assertThat(result.getAdaptedRequest().getAttachments()).as("the prompt's image is the third").isEmpty();
        assertThat(result.getDroppedAttachments()).singleElement().satisfies(dropped -> assertThat(dropped.getIndex()).isEqualTo(2));
    }

    @Test
    void whenNothingIsDroppedTheRequestIsLeftAsItWas() {
        Request request = Request.builder()
                .history(List.of(Message.user("one", List.of(PNG, PNG))))
                .attachments(List.of(PNG))
                .prompt("p")
                .build();

        NegotiationResult result = negotiate(imagesOnly(null), request);

        assertThat(result.getDroppedFeatures()).doesNotContain("attachments");
        assertThat(result.getAdaptedRequest().getHistory()).isEqualTo(request.getHistory());
        assertThat(result.getAdaptedRequest().getAttachments()).hasSize(1);
    }

    @Test
    void requiringAttachmentsCountsTheHistorysToo() {
        ModelEntry textOnly = ModelEntry.builder().provider(Provider.OPENROUTER).model("m").inputMediaTypes(List.of()).build();
        Request request = Request.builder().history(List.of(Message.user("seen", List.of(PNG)))).prompt("p").build();

        assertThat(CapabilityNegotiator.unmetRequiredFeatures(textOnly, request, StructuredOutputStrategy.AUTO,
                java.util.Set.of(Feature.ATTACHMENTS))).containsExactly(Feature.ATTACHMENTS);
    }

    @Test
    void onlyUserMessagesCanCarryAttachments() {
        Message assistant = Message.builder().role(Role.ASSISTANT).content("x").attachments(List.of(PNG)).build();
        LlmRouter router = new LlmRouter(List.of(adapter(Usage.builder().build())));

        assertThatThrownBy(() -> router.complete(Request.builder().history(List.of(assistant)).prompt("p").build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only USER history messages can carry attachments");
    }

    @Test
    void aNullCacheLifetimeIsRejected() {
        LlmRouter router = new LlmRouter(List.of(adapter(Usage.builder().build())));

        assertThatThrownBy(() -> router.complete(Request.builder().prompt("p")
                .config(RouterConfig.builder().promptCacheTtl(null).build()).build()))
                .isInstanceOf(InvalidConfigException.class)
                .hasMessageContaining("promptCacheTtl");
    }

    @Test
    void theEstimateCountsTheHistorysAttachments() {
        ModelEntry opus = ModelCapabilityTable.findModel(Provider.ANTHROPIC, "claude-opus-5-5").orElseThrow();
        Request plain = Request.builder().history(List.of(Message.user("seen"))).prompt("p").build();
        Request withPdf = Request.builder().history(List.of(Message.user("seen", List.of(PDF)))).prompt("p").build();

        assertThat(RequestEstimator.inputTokens(withPdf, opus)).isGreaterThan(RequestEstimator.inputTokens(plain, opus));
    }

    @Test
    void historyAttachmentsSurviveAJsonRoundTrip() {
        ObjectMapper json = new ObjectMapper();
        Message message = Message.user("deck", List.of(PNG));

        Message read = json.readValue(json.writeValueAsString(message), Message.class);

        assertThat(read).isEqualTo(message);
        assertThat(json.readValue("{\"role\":\"user\",\"content\":\"hi\"}", Message.class).getAttachments())
                .as("older JSON without attachments").isEmpty();
    }

    @Test
    void cacheFieldsReachTheResponseAndBothListenerEventsPricedAtTheTablesRates() {
        Usage reported = Usage.builder().inputTokens(100_000).cacheReadTokens(90_000).cacheWriteTokens(8_000)
                .cacheWriteLongTtlTokens(3_000).outputTokens(1_500).reasoningTokens(700).build();
        List<AttemptUsage> attempts = new CopyOnWriteArrayList<>();
        List<CallUsage> calls = new CopyOnWriteArrayList<>();
        LlmRouter router = new LlmRouter(List.of(adapter(reported))).withUsageListener(new UsageListener() {
            @Override
            public void onAttempt(AttemptUsage attempt) {
                attempts.add(attempt);
            }

            @Override
            public void onCall(CallUsage call) {
                calls.add(call);
            }
        });
        Request request = Request.builder().prompt("p")
                .config(RouterConfig.builder().route(List.of(RouteEntry.of(Provider.ANTHROPIC, "claude-opus-5-5"))).build())
                .build();

        for (Response response : List.of(router.complete(request), router.completeAsync(request).join(),
                router.completeStreaming(request, delta -> { }))) {
            Usage usage = response.getUsage();
            assertThat(usage.getCacheReadTokens()).isEqualTo(90_000);
            assertThat(usage.getCacheWriteTokens()).isEqualTo(8_000);
            assertThat(usage.getCacheWriteLongTtlTokens()).isEqualTo(3_000);
            assertThat(usage.getReasoningTokens()).isEqualTo(700);
            // 2,000 plain at $4, 90,000 read at $0.20, 5,000 written at $5 and 3,000 at $8, 1,500 output at $20
            double expected = (2_000 * 4 + 90_000 * 0.2 + 5_000 * 5 + 3_000 * 8 + 1_500 * 20) / 1_000_000.0;
            assertThat(usage.getEstimatedCostUsdMicros()).isEqualTo(Math.round(expected * 1_000_000));
        }
        assertThat(attempts).hasSize(3).allSatisfy(attempt -> assertThat(attempt.getUsage().getCacheWriteLongTtlTokens()).isEqualTo(3_000));
        assertThat(calls).hasSize(3).allSatisfy(call -> {
            assertThat(call.getUsage().getCacheReadTokens()).isEqualTo(90_000);
            assertThat(call.getUsage()).isEqualTo(call.getAttempts().getLast().getUsage());
        });
    }

    @Test
    void aCostTheProviderReportsWinsButItsCacheTokensStillComeThrough() {
        Usage reported = Usage.builder().inputTokens(1_000).cacheReadTokens(800).outputTokens(10).estimatedCostUsdMicros(4_321).build();
        LlmRouter router = new LlmRouter(List.of(adapter(reported, Provider.PERPLEXITY)));

        Response response = router.complete(Request.builder().prompt("p")
                .config(RouterConfig.builder().route(List.of(RouteEntry.of(Provider.PERPLEXITY, "perplexity/sonar"))).build())
                .build());

        assertThat(response.getUsage().getEstimatedCostUsdMicros()).isEqualTo(4_321);
        assertThat(response.getUsage().getCacheReadTokens()).isEqualTo(800);
    }

    @Test
    void anExhaustedCallStillThrows() {
        LlmRouter router = new LlmRouter(List.of());
        assertThatThrownBy(() -> router.complete(Request.builder().prompt("p")
                .config(RouterConfig.builder().route(List.of(RouteEntry.of(Provider.ANTHROPIC, "claude-opus-5-5"))).build())
                .build())).isInstanceOf(RouterExhaustedException.class);
    }

    private static ProviderAdapter adapter(Usage usage) {
        return adapter(usage, Provider.ANTHROPIC);
    }

    private static ProviderAdapter adapter(Usage usage, Provider provider) {
        return new ProviderAdapter() {
            @Override
            public Provider id() {
                return provider;
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public Response send(String model, Request request) {
                return Response.builder().content("ok").usage(usage).build();
            }

            @Override
            public Response sendStreaming(String model, Request request, Consumer<String> onText) {
                onText.accept("ok");
                return send(model, request);
            }
        };
    }
}
