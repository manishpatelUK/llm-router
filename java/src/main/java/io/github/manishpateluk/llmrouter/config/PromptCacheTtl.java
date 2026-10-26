package io.github.manishpateluk.llmrouter.config;

/** How long a provider keeps a prompt-cache entry, where the caller can choose — see {@code RouterConfig.promptCacheTtl} and §4.3. */
public enum PromptCacheTtl {

    /** The provider's default lifetime: five minutes on Anthropic, refreshed by every read. */
    DEFAULT,

    /** The provider's longer lifetime: one hour on Anthropic, whose writes cost more. */
    LONG
}
