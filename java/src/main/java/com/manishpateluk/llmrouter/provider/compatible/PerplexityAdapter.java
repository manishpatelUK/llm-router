package com.manishpateluk.llmrouter.provider.compatible;

import com.manishpateluk.llmrouter.provider.Provider;

/** {@link OpenAiCompatibleHttpAdapter} for Perplexity — no official Java SDK exists. */
public final class PerplexityAdapter extends OpenAiCompatibleHttpAdapter {

    private static final String CHAT_COMPLETIONS_URL = "https://api.perplexity.ai/chat/completions";

    public PerplexityAdapter(String apiKey) {
        super(apiKey);
    }

    public PerplexityAdapter(String apiKey, HttpTransport transport) {
        super(apiKey, transport);
    }

    @Override
    public Provider id() {
        return Provider.PERPLEXITY;
    }

    @Override
    protected String chatCompletionsUrl() {
        return CHAT_COMPLETIONS_URL;
    }
}
