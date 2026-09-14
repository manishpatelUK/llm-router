package com.manishpateluk.llmrouter.provider;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves provider credentials from environment variables per {@code LIBRARY_SPEC.md} §6.1: a
 * library-namespaced variable first, then that provider's own conventional variable(s).
 *
 * <p>Each provider's result is computed lazily, on first {@link #resolve} call for that
 * provider, and cached for the lifetime of this resolver instance — matching the spec's
 * "detected once, lazily, cached for the process lifetime" behavior for the common case of one
 * {@code LlmRouter} constructed at application startup and reused.
 *
 * <p>The environment lookup itself is injectable ({@link #CredentialResolver(Function)}) rather
 * than hardwired to {@link System#getenv(String)}, specifically so tests can substitute a fake
 * lookup instead of mutating real process environment variables.
 */
public final class CredentialResolver {

    private static final Map<Provider, List<String>> ENV_VAR_CANDIDATES = Map.of(
            Provider.ANTHROPIC, List.of("LLM_ROUTER_ANTHROPIC_API_KEY", "ANTHROPIC_API_KEY"),
            Provider.OPENAI, List.of("LLM_ROUTER_OPENAI_API_KEY", "OPENAI_API_KEY"),
            Provider.PERPLEXITY, List.of("LLM_ROUTER_PERPLEXITY_API_KEY", "PERPLEXITY_API_KEY"),
            Provider.NVIDIA, List.of("LLM_ROUTER_NVIDIA_API_KEY", "NVIDIA_API_KEY"),
            Provider.HUGGINGFACE, List.of("LLM_ROUTER_HUGGINGFACE_API_KEY", "HF_TOKEN", "HUGGINGFACE_API_KEY"),
            Provider.OPENROUTER, List.of("LLM_ROUTER_OPENROUTER_API_KEY", "OPENROUTER_API_KEY"));

    private final Function<String, String> envLookup;
    private final Map<Provider, Optional<String>> cache = new ConcurrentHashMap<>();

    /** Resolves against the real process environment ({@link System#getenv(String)}). */
    public CredentialResolver() {
        this(System::getenv);
    }

    /** @param envLookup returns the value of an env var by name, or {@code null}/empty if unset */
    public CredentialResolver(Function<String, String> envLookup) {
        this.envLookup = Objects.requireNonNull(envLookup, "envLookup must not be null");
    }

    /**
     * Resolves the credential for a provider, checking its primary variable then fallback(s) in
     * order, treating an unset or empty value as absent.
     */
    public Optional<String> resolve(Provider provider) {
        Objects.requireNonNull(provider, "provider must not be null");
        return cache.computeIfAbsent(provider, this::doResolve);
    }

    /** @return {@code true} if either the primary or a fallback variable resolves to a non-empty value */
    public boolean isAvailable(Provider provider) {
        return resolve(provider).isPresent();
    }

    /**
     * Every provider with a detected credential, in the §6.2 default preference order (the
     * order {@link Provider}'s constants are declared in).
     */
    public Set<Provider> availableProviders() {
        return Arrays.stream(Provider.values())
                .filter(this::isAvailable)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Optional<String> doResolve(Provider provider) {
        for (String envVar : ENV_VAR_CANDIDATES.get(provider)) {
            String value = envLookup.apply(envVar);
            if (value != null && !value.isEmpty()) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }
}
