package io.github.manishpateluk.llmrouter.provider.compatible;

import java.util.Base64;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import io.github.manishpateluk.llmrouter.model.Attachment;
import io.github.manishpateluk.llmrouter.provider.Provider;

/** {@link OpenAiCompatibleHttpAdapter} for Perplexity — no official Java SDK exists. */
public final class PerplexityAdapter extends OpenAiCompatibleHttpAdapter {

    private static final JsonMapper JSON = JsonMapper.builder().build();

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

    /**
     * Perplexity doesn't document {@code stream_options}, so streaming requests don't send it;
     * usage is read from whichever streamed chunk reports it.
     */
    @Override
    protected boolean requestsStreamUsage() {
        return false;
    }

    /**
     * Perplexity's document part: {@code {"type": "file_url", "file_url": {"url": <base64>},
     * "file_name": ...}}, with the base64 content given bare (no {@code data:} prefix). It takes
     * PDF, DOC, DOCX, TXT and RTF files.
     */
    @Override
    protected ObjectNode documentPart(Attachment document, int position) {
        ObjectNode part = JSON.createObjectNode();
        part.put("type", "file_url");
        part.putObject("file_url").put("url", Base64.getEncoder().encodeToString(document.getData()));
        part.put("file_name", document.getFilename() != null ? document.getFilename() : "document-" + position);
        return part;
    }
}
