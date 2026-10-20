package io.github.manishpateluk.llmrouter.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.Headers;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.RateLimitException;
import com.openai.client.OpenAIClient;

import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.provider.anthropic.AnthropicAdapter;
import io.github.manishpateluk.llmrouter.provider.compatible.HttpTransport;
import io.github.manishpateluk.llmrouter.provider.compatible.HttpTransport.HttpResponseRecord;
import io.github.manishpateluk.llmrouter.provider.compatible.NvidiaAdapter;
import io.github.manishpateluk.llmrouter.provider.compatible.ProviderHttpException;
import io.github.manishpateluk.llmrouter.provider.openai.OpenAiAdapter;

import org.junit.jupiter.api.Test;

/** Which failures each adapter calls transient, and the {@code Retry-After} it reads (§5.5). */
class TransientFailuresTest {

    private static List<String> header(Map<String, String> headers, String name) {
        String value = headers.get(name);
        return value == null ? List.of() : List.of(value);
    }

    @Test
    void retryAfterReadsSecondsMillisecondsAndHttpDates() {
        assertThat(TransientFailures.retryAfter(name -> header(Map.of("retry-after", "2"), name))).isEqualTo(Duration.ofSeconds(2));
        assertThat(TransientFailures.retryAfter(name -> header(Map.of("retry-after", "0.5"), name))).isEqualTo(Duration.ofMillis(500));
        assertThat(TransientFailures.retryAfter(name -> header(Map.of("retry-after-ms", "750", "retry-after", "9"), name)))
                .isEqualTo(Duration.ofMillis(750));

        String inTenSeconds = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(10));
        assertThat(TransientFailures.retryAfter(name -> header(Map.of("retry-after", inTenSeconds), name)))
                .isBetween(Duration.ofSeconds(8), Duration.ofSeconds(10));
    }

    @Test
    void retryAfterIsNullWhenAbsentOrUnreadableAndNeverNegative() {
        assertThat(TransientFailures.retryAfter(name -> List.of())).isNull();
        assertThat(TransientFailures.retryAfter(name -> null)).isNull();
        assertThat(TransientFailures.retryAfter(name -> header(Map.of("retry-after", "soon"), name))).isNull();
        assertThat(TransientFailures.retryAfter(name -> header(Map.of("retry-after", "-3"), name))).isEqualTo(Duration.ZERO);
    }

    @Test
    void transientStatusesAreRateLimitsTimeoutsAndServerErrors() {
        assertThat(List.of(408, 429, 500, 502, 503, 504, 529)).allMatch(TransientFailures::isTransientStatus);
        assertThat(List.of(400, 401, 403, 404, 409, 422, 501)).noneMatch(TransientFailures::isTransientStatus);
    }

    @Test
    void responseRecordHeadersMatchCaseInsensitivelyAndTheTwoArgumentFormStillWorks() {
        HttpResponseRecord withHeaders = new HttpResponseRecord(429, "slow down", Map.of("Retry-After", List.of("3")));

        assertThat(withHeaders.header("retry-after")).containsExactly("3");
        assertThat(withHeaders.retryAfter()).isEqualTo(Duration.ofSeconds(3));
        assertThat(new HttpResponseRecord(200, "ok").headers()).isEmpty();
        assertThat(new HttpResponseRecord(503, "down").retryAfter()).isNull();
    }

    @Test
    void anHttpAdapterCarriesRetryAfterOnItsException() {
        HttpTransport transport = mock(HttpTransport.class);
        when(transport.send(any())).thenReturn(new HttpResponseRecord(429, "slow down", Map.of("retry-after", List.of("4"))));
        NvidiaAdapter adapter = new NvidiaAdapter("key", transport);

        assertThatThrownBy(() -> adapter.send("some-model", Request.builder().prompt("Hello").build()))
                .isInstanceOfSatisfying(ProviderHttpException.class, e -> {
                    assertThat(e.statusCode()).isEqualTo(429);
                    assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(4));
                    assertThat(adapter.isTransient(e)).isTrue();
                    assertThat(adapter.retryAfter(e)).isEqualTo(Duration.ofSeconds(4));
                });
    }

    @Test
    void theDefaultClassificationCoversHttpStatusesAndIoErrorsAnywhereInTheCauseChain() {
        ProviderAdapter adapter = new NvidiaAdapter("key", mock(HttpTransport.class));

        assertThat(adapter.isTransient(new ProviderHttpException(503, "down"))).isTrue();
        assertThat(adapter.isTransient(new CompletionException(new ProviderHttpException(502, "bad gateway")))).isTrue();
        assertThat(adapter.isTransient(new ProviderHttpException(401, "unauthorized"))).isFalse();
        assertThat(adapter.isTransient(new UncheckedIOException(new IOException("reset")))).isTrue();
        assertThat(adapter.isTransient(new IllegalStateException("bug"))).isFalse();
        assertThat(adapter.retryAfter(new IllegalStateException("bug"))).isNull();
    }

    @Test
    void theAnthropicAdapterRecognisesItsSdksExceptions() {
        AnthropicAdapter adapter = new AnthropicAdapter(mock(AnthropicClient.class));
        RateLimitException rateLimited = RateLimitException.builder()
                .headers(Headers.builder().put("retry-after", "7").build())
                .body(JsonValue.from(Map.of()))
                .build();
        InternalServerException overloaded = InternalServerException.builder()
                .statusCode(529)
                .headers(Headers.builder().build())
                .body(JsonValue.from(Map.of()))
                .build();
        BadRequestException badRequest = BadRequestException.builder()
                .headers(Headers.builder().build())
                .body(JsonValue.from(Map.of()))
                .build();

        assertThat(adapter.isTransient(rateLimited)).isTrue();
        assertThat(adapter.retryAfter(rateLimited)).isEqualTo(Duration.ofSeconds(7));
        assertThat(adapter.isTransient(overloaded)).isTrue();
        assertThat(adapter.retryAfter(overloaded)).isNull();
        assertThat(adapter.isTransient(badRequest)).isFalse();
        assertThat(adapter.isTransient(new AnthropicIoException("connection reset"))).isTrue();
    }

    @Test
    void theOpenAiAdapterRecognisesItsSdksExceptions() {
        OpenAiAdapter adapter = new OpenAiAdapter(mock(OpenAIClient.class));
        com.openai.errors.RateLimitException rateLimited = com.openai.errors.RateLimitException.builder()
                .headers(com.openai.core.http.Headers.builder().put("retry-after-ms", "1500").build())
                .build();
        com.openai.errors.BadRequestException badRequest = com.openai.errors.BadRequestException.builder()
                .headers(com.openai.core.http.Headers.builder().build())
                .build();

        assertThat(adapter.isTransient(rateLimited)).isTrue();
        assertThat(adapter.retryAfter(rateLimited)).isEqualTo(Duration.ofMillis(1500));
        assertThat(adapter.isTransient(badRequest)).isFalse();
        assertThat(adapter.isTransient(new com.openai.errors.OpenAIIoException("connection reset"))).isTrue();
    }
}
