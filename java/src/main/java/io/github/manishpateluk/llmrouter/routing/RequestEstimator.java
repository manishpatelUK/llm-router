package io.github.manishpateluk.llmrouter.routing;

import java.util.Map;
import java.util.regex.Pattern;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import io.github.manishpateluk.llmrouter.capability.ModelEntry;
import io.github.manishpateluk.llmrouter.model.Attachment;
import io.github.manishpateluk.llmrouter.model.Message;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.ToolCall;
import io.github.manishpateluk.llmrouter.model.ToolDefinition;
import io.github.manishpateluk.llmrouter.provider.Provider;

/**
 * Estimates what a whole request costs on a given model, before it's sent — see
 * {@code LIBRARY_SPEC.md} §5.3. Input counts the prompt, system instructions, history (including
 * tool calls), tool definitions, the response schema, and every attachment the model accepts, at
 * that provider's documented token rate for images and PDF pages. Output is the request's
 * {@code maxOutputTokens}, or {@link #DEFAULT_EXPECTED_OUTPUT_TOKENS} when unset, priced at the
 * model's output rate.
 *
 * <p>Text is counted with {@link TokenEstimator}'s characters-per-token heuristic, so this is a
 * ranking signal and a context-window sanity check, not a bill.
 */
public final class RequestEstimator {

    /** Output assumed when a request sets no {@code maxOutputTokens}: a substantial answer, not the model's maximum. */
    public static final int DEFAULT_EXPECTED_OUTPUT_TOKENS = 1_000;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Per-message framing (role markers etc.) providers add around each turn. */
    private static final int TOKENS_PER_MESSAGE = 4;

    private RequestEstimator() {
    }

    /** Tokens for everything in {@code request} except its attachments. */
    public static int textTokens(Request request) {
        long tokens = TokenEstimator.estimateTokens(request.getPrompt()) + TOKENS_PER_MESSAGE;
        tokens += TokenEstimator.estimateTokens(request.getSystemInstructions());
        for (Message message : request.getHistory()) {
            tokens += TOKENS_PER_MESSAGE + TokenEstimator.estimateTokens(message.getContent());
            for (ToolCall call : message.getToolCalls()) {
                tokens += TokenEstimator.estimateTokens(call.getName()) + TokenEstimator.estimateTokens(json(call.getArguments()));
            }
        }
        for (ToolDefinition tool : request.getTools()) {
            tokens += TokenEstimator.estimateTokens(tool.getName())
                    + TokenEstimator.estimateTokens(tool.getDescription())
                    + TokenEstimator.estimateTokens(json(tool.getParameters()));
        }
        if (request.getResponseSchema() != null) {
            tokens += TokenEstimator.estimateTokens(json(request.getResponseSchema()));
        }
        return saturate(tokens);
    }

    /**
     * Estimated input tokens for {@code request} on {@code model}: {@link #textTokens} plus the
     * attachments the model accepts (§4 drops the rest, so they cost nothing).
     */
    public static int inputTokens(Request request, ModelEntry model) {
        long tokens = textTokens(request);
        for (Attachment attachment : request.everyAttachment()) {
            if (model.supportsMediaType(attachment.getMediaType())) {
                tokens += attachmentTokens(model.getProvider(), model.getModel(), attachment);
            }
        }
        return saturate(tokens);
    }

    /** {@code request.maxOutputTokens} (capped at the model's maximum), else {@link #DEFAULT_EXPECTED_OUTPUT_TOKENS}. */
    public static int expectedOutputTokens(Request request, ModelEntry model) {
        int expected = request.getMaxOutputTokens() != null ? request.getMaxOutputTokens() : DEFAULT_EXPECTED_OUTPUT_TOKENS;
        return model.getMaxOutputTokens() > 0 ? Math.min(expected, model.getMaxOutputTokens()) : expected;
    }

    /** Estimated cost in US dollars of sending {@code request} to {@code model}: input plus expected output. */
    public static double estimatedCostUsd(Request request, ModelEntry model) {
        return inputTokens(request, model) / 1_000_000.0 * model.getInputCostPerMillionTokens()
                + expectedOutputTokens(request, model) / 1_000_000.0 * model.getOutputCostPerMillionTokens();
    }

    /** Estimated input tokens one attachment costs on {@code provider}/{@code model}. */
    public static int attachmentTokens(Provider provider, String model, Attachment attachment) {
        String mediaType = ModelEntry.normalizeMediaType(attachment.getMediaType());
        byte[] data = attachment.getData() == null ? new byte[0] : attachment.getData();
        Rates rates = Rates.of(provider, model);
        if (mediaType.startsWith("image/")) {
            return rates.imageTokens(AttachmentInspector.imageDimensions(data));
        }
        if (mediaType.equals("application/pdf")) {
            return AttachmentInspector.estimatedPdfPages(data) * rates.tokensPerPdfPage;
        }
        // Text-like documents are read as text.
        return (int) Math.ceil(data.length / 4.0);
    }

    /**
     * Each provider family's documented attachment token rates (§5.3):
     * <ul>
     *   <li>Anthropic: {@code ceil(w/28) × ceil(h/28)} tokens. Claude 4.7 and later downscale to
     *       2576 px on the long edge and at most 4,784 tokens; earlier models (e.g. Haiku 4.5) to
     *       1568 px and 1,568 tokens. A PDF page is its text (1,500-3,000 tokens) plus the page as
     *       an image: about 3,800.</li>
     *   <li>OpenAI: {@code ceil(w/32) × ceil(h/32)} patches, scaled down to at most 2,500, times
     *       1.2. A PDF page is its text plus a page image, with no published figure: about 3,000.</li>
     *   <li>Gemini 3 (OpenRouter {@code google/gemini-3*}): 1,120 tokens per image and 560 per PDF
     *       page at the default media resolution; older Gemini, 258 per 768 px tile and per page.</li>
     *   <li>Everyone else (Perplexity's published rate): {@code w × h / 750} after downscaling to
     *       1568 px, and about 1,000 tokens per PDF page, which these providers read as text.</li>
     * </ul>
     */
    private enum Rates {
        ANTHROPIC_HIGH_RES(3_800),
        ANTHROPIC_STANDARD(3_800),
        OPENAI(3_000),
        GEMINI_3(560),
        GEMINI(258),
        OTHER(1_000);

        /** Assumed edge length, in pixels, of an image whose header can't be read. */
        private static final int UNKNOWN_IMAGE_SIZE = 1024;

        /** Anthropic models before Claude 4.7, which use the standard (1568 px) image tier. */
        private static final Pattern ANTHROPIC_STANDARD_TIER =
                Pattern.compile("claude-(3.*|(opus|sonnet|haiku)-4(-[0-6])?(-\\d{8})?)");

        final int tokensPerPdfPage;

        Rates(int tokensPerPdfPage) {
            this.tokensPerPdfPage = tokensPerPdfPage;
        }

        static Rates of(Provider provider, String model) {
            String id = model == null ? "" : model;
            if (provider == Provider.ANTHROPIC) {
                return ANTHROPIC_STANDARD_TIER.matcher(id).matches() ? ANTHROPIC_STANDARD : ANTHROPIC_HIGH_RES;
            }
            if (provider == Provider.OPENAI) {
                return OPENAI;
            }
            if (provider == Provider.OPENROUTER && id.startsWith("google/")) {
                return id.startsWith("google/gemini-3") ? GEMINI_3 : GEMINI;
            }
            return OTHER;
        }

        int imageTokens(int[] dimensions) {
            double width = dimensions == null ? UNKNOWN_IMAGE_SIZE : Math.max(1, dimensions[0]);
            double height = dimensions == null ? UNKNOWN_IMAGE_SIZE : Math.max(1, dimensions[1]);
            return switch (this) {
                case ANTHROPIC_HIGH_RES -> patches(width, height, 28, 2576, 4_784, 1.0);
                case ANTHROPIC_STANDARD -> patches(width, height, 28, 1568, 1_568, 1.0);
                case OPENAI -> patches(width, height, 32, Integer.MAX_VALUE, 2_500, 1.2);
                case GEMINI_3 -> 1_120;
                case GEMINI -> width <= 384 && height <= 384
                        ? 258
                        : 258 * (int) (Math.ceil(width / 768) * Math.ceil(height / 768));
                case OTHER -> {
                    double scale = Math.min(1.0, 1568 / Math.max(width, height));
                    yield (int) Math.ceil(width * scale * height * scale / 750);
                }
            };
        }

        /**
         * {@code ceil(w/patch) × ceil(h/patch)} after scaling the image down to fit
         * {@code maxEdge} and {@code maxPatches}, times {@code multiplier}.
         */
        private static int patches(double width, double height, int patch, int maxEdge, int maxPatches, double multiplier) {
            double scale = Math.min(1.0, Math.min((double) maxEdge / Math.max(width, height),
                    Math.sqrt(maxPatches * (double) patch * patch / (width * height))));
            long count = (long) (Math.ceil(width * scale / patch) * Math.ceil(height * scale / patch));
            return (int) Math.ceil(Math.min(count, maxPatches) * multiplier);
        }
    }

    private static String json(Map<String, Object> value) {
        if (value == null) {
            return "";
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (JacksonException e) {
            return String.valueOf(value);
        }
    }

    private static int saturate(long tokens) {
        return (int) Math.min(Integer.MAX_VALUE, tokens);
    }
}
