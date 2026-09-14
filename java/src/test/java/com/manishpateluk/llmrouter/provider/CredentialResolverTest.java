package com.manishpateluk.llmrouter.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class CredentialResolverTest {

    private static CredentialResolver resolverFor(Map<String, String> env) {
        return new CredentialResolver(env::get);
    }

    @Test
    void resolvesPrimaryVariableWhenSet() {
        CredentialResolver resolver = resolverFor(Map.of("LLM_ROUTER_OPENAI_API_KEY", "primary-key"));

        assertThat(resolver.resolve(Provider.OPENAI)).contains("primary-key");
        assertThat(resolver.isAvailable(Provider.OPENAI)).isTrue();
    }

    @Test
    void fallsBackToConventionalVariableWhenPrimaryUnset() {
        CredentialResolver resolver = resolverFor(Map.of("OPENAI_API_KEY", "fallback-key"));

        assertThat(resolver.resolve(Provider.OPENAI)).contains("fallback-key");
    }

    @Test
    void huggingFaceChecksTwoFallbacksInOrder() {
        CredentialResolver hfTokenOnly = resolverFor(Map.of("HF_TOKEN", "hf-token-value"));
        assertThat(hfTokenOnly.resolve(Provider.HUGGINGFACE)).contains("hf-token-value");

        CredentialResolver secondFallbackOnly = resolverFor(Map.of("HUGGINGFACE_API_KEY", "hf-api-key-value"));
        assertThat(secondFallbackOnly.resolve(Provider.HUGGINGFACE)).contains("hf-api-key-value");

        Map<String, String> both = new LinkedHashMap<>();
        both.put("HF_TOKEN", "hf-token-value");
        both.put("HUGGINGFACE_API_KEY", "hf-api-key-value");
        assertThat(resolverFor(both).resolve(Provider.HUGGINGFACE))
                .as("HF_TOKEN takes priority over HUGGINGFACE_API_KEY")
                .contains("hf-token-value");
    }

    @Test
    void neitherVariableSetMeansUnavailable() {
        CredentialResolver resolver = resolverFor(Map.of());

        assertThat(resolver.resolve(Provider.ANTHROPIC)).isEmpty();
        assertThat(resolver.isAvailable(Provider.ANTHROPIC)).isFalse();
    }

    @Test
    void emptyStringValueIsTreatedAsAbsent() {
        CredentialResolver resolver = resolverFor(Map.of("ANTHROPIC_API_KEY", ""));

        assertThat(resolver.isAvailable(Provider.ANTHROPIC)).isFalse();
    }

    @Test
    void availableProvidersPreservesDefaultPreferenceOrder() {
        Map<String, String> env = Map.of(
                "OPENROUTER_API_KEY", "k1",
                "ANTHROPIC_API_KEY", "k2",
                "NVIDIA_API_KEY", "k3");

        assertThat(resolverFor(env).availableProviders())
                .containsExactly(Provider.ANTHROPIC, Provider.NVIDIA, Provider.OPENROUTER);
    }
}
