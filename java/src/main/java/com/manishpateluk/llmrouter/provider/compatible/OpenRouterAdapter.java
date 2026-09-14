package com.manishpateluk.llmrouter.provider.compatible;

import com.manishpateluk.llmrouter.provider.Provider;

/** {@link OpenAiCompatibleHttpAdapter} for OpenRouter — no official Java SDK exists. */
public final class OpenRouterAdapter extends OpenAiCompatibleHttpAdapter {

    private static final String CHAT_COMPLETIONS_URL = "https://openrouter.ai/api/v1/chat/completions";

    public OpenRouterAdapter(String apiKey) {
        super(apiKey);
    }

    public OpenRouterAdapter(String apiKey, HttpTransport transport) {
        super(apiKey, transport);
    }

    @Override
    public Provider id() {
        return Provider.OPENROUTER;
    }

    @Override
    protected String chatCompletionsUrl() {
        return CHAT_COMPLETIONS_URL;
    }
}
