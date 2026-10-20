package io.github.manishpateluk.llmrouter.provider.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.provider.CountingHttpServer;

/** The client {@link AnthropicAdapter} builds leaves retrying to the router (§5.5). */
class AnthropicClientRetriesTest {

    @Test
    void aServerErrorIsSentOnceWithoutSdkRetries() throws Exception {
        try (CountingHttpServer server = new CountingHttpServer(503,
                "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"busy\"}}")) {
            AnthropicAdapter adapter = new AnthropicAdapter(AnthropicAdapter.newClient("test-key", server.baseUrl()));

            assertThatThrownBy(() -> adapter.send("claude-test", Request.builder().prompt("hi").build()))
                    .satisfies(e -> assertThat(adapter.isTransient(e)).isTrue());
            assertThat(server.requests()).isEqualTo(1);
        }
    }

    @Test
    void noKeyBuildsNoClient() {
        assertThat(AnthropicAdapter.newClient(" ", null)).isNull();
        assertThat(new AnthropicAdapter((String) null).isAvailable()).isFalse();
    }
}
