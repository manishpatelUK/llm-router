package com.manishpateluk.llmrouter.provider.compatible;

import com.manishpateluk.llmrouter.provider.Provider;

/** {@link OpenAiCompatibleHttpAdapter} for NVIDIA NIM — no official Java SDK exists. */
public final class NvidiaAdapter extends OpenAiCompatibleHttpAdapter {

    private static final String CHAT_COMPLETIONS_URL = "https://integrate.api.nvidia.com/v1/chat/completions";

    public NvidiaAdapter(String apiKey) {
        super(apiKey);
    }

    public NvidiaAdapter(String apiKey, HttpTransport transport) {
        super(apiKey, transport);
    }

    @Override
    public Provider id() {
        return Provider.NVIDIA;
    }

    @Override
    protected String chatCompletionsUrl() {
        return CHAT_COMPLETIONS_URL;
    }
}
