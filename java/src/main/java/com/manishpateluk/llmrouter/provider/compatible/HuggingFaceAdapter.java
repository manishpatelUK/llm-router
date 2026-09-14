package com.manishpateluk.llmrouter.provider.compatible;

import com.manishpateluk.llmrouter.provider.Provider;

/**
 * {@link OpenAiCompatibleHttpAdapter} for Hugging Face, via its OpenAI-compatible Inference
 * Providers router endpoint — no official Java SDK exists.
 */
public final class HuggingFaceAdapter extends OpenAiCompatibleHttpAdapter {

    private static final String CHAT_COMPLETIONS_URL = "https://router.huggingface.co/v1/chat/completions";

    public HuggingFaceAdapter(String apiKey) {
        super(apiKey);
    }

    public HuggingFaceAdapter(String apiKey, HttpTransport transport) {
        super(apiKey, transport);
    }

    @Override
    public Provider id() {
        return Provider.HUGGINGFACE;
    }

    @Override
    protected String chatCompletionsUrl() {
        return CHAT_COMPLETIONS_URL;
    }
}
