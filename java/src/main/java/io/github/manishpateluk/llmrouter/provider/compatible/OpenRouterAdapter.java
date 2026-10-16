package io.github.manishpateluk.llmrouter.provider.compatible;

import tools.jackson.databind.node.ObjectNode;
import io.github.manishpateluk.llmrouter.provider.Provider;

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

    /**
     * OpenRouter's unified {@code reasoning} object: {@code {"effort": ...}} or, for a thinking
     * budget, {@code {"max_tokens": ...}} — never both.
     */
    @Override
    protected void applyReasoning(ObjectNode body, String effort, Integer budgetTokens) {
        ObjectNode reasoning = body.putObject("reasoning");
        if (effort != null) {
            reasoning.put("effort", effort);
        } else {
            reasoning.put("max_tokens", budgetTokens);
        }
    }
}
