package io.github.manishpateluk.llmrouter;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.model.Usage;
import io.github.manishpateluk.llmrouter.provider.Provider;
import io.github.manishpateluk.llmrouter.provider.ProviderAdapter;

/**
 * A test adapter that records every model and request it's sent, answers "ok" for the models in
 * {@code succeeds}, and fails for every other.
 */
public final class RecordingAdapter implements ProviderAdapter {

    public record Call(String model, Request request) {
    }

    private final Provider provider;
    private final Set<String> succeeds;
    private final List<Call> calls = new CopyOnWriteArrayList<>();

    public RecordingAdapter(Provider provider, String... succeeds) {
        this.provider = provider;
        this.succeeds = Set.of(succeeds);
    }

    public List<Call> calls() {
        return calls;
    }

    public List<String> models() {
        return calls.stream().map(Call::model).toList();
    }

    @Override
    public Provider id() {
        return provider;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public Response send(String model, Request adaptedRequest) {
        calls.add(new Call(model, adaptedRequest));
        if (!succeeds.contains(model)) {
            throw new IllegalStateException(provider + "/" + model + " is down");
        }
        return Response.builder()
                .content("ok from " + model)
                .usage(Usage.builder().inputTokens(10).outputTokens(5).build())
                .build();
    }
}
