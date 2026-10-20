package io.github.manishpateluk.llmrouter.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.provider.CountingHttpServer;

/** The client {@link OpenAiAdapter} builds leaves retrying to the router (§5.5). */
class OpenAiClientRetriesTest {

    @Test
    void aServerErrorIsSentOnceWithoutSdkRetries() throws Exception {
        try (CountingHttpServer server = new CountingHttpServer(503,
                "{\"error\":{\"message\":\"busy\",\"type\":\"server_error\"}}")) {
            OpenAiAdapter adapter = new OpenAiAdapter(OpenAiAdapter.newClient("test-key", server.baseUrl()));

            assertThatThrownBy(() -> adapter.send("gpt-test", Request.builder().prompt("hi").build()))
                    .satisfies(e -> assertThat(adapter.isTransient(e)).isTrue());
            assertThat(server.requests()).isEqualTo(1);
        }
    }

    @Test
    void noKeyBuildsNoClient() {
        assertThat(OpenAiAdapter.newClient(null, null)).isNull();
        assertThat(new OpenAiAdapter((String) null).isAvailable()).isFalse();
    }
}
