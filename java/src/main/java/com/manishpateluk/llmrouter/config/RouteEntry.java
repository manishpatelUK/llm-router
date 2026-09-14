package com.manishpateluk.llmrouter.config;

import com.manishpateluk.llmrouter.provider.Provider;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * One candidate in a {@code RouterConfig.route} fallback chain — see {@code LIBRARY_SPEC.md}
 * §5. A {@code null} {@code model} means "provider only": resolved to a concrete model at call
 * time via {@code thinkingLevel} and the Model Capability Table's selection heuristic (§7.2). A
 * non-null {@code model} is a fully-specified candidate, used exactly as given regardless of
 * {@code thinkingLevel}.
 */
@Value
@Builder
@Jacksonized
public class RouteEntry {

    Provider provider;
    String model;

    public static RouteEntry of(Provider provider) {
        return RouteEntry.builder().provider(provider).build();
    }

    public static RouteEntry of(Provider provider, String model) {
        return RouteEntry.builder().provider(provider).model(model).build();
    }

    public boolean isProviderOnly() {
        return model == null;
    }
}
