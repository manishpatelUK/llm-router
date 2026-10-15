package io.github.manishpateluk.llmrouter.routing;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads just enough of an attachment's bytes to size it: an image's pixel dimensions from its
 * header, and a PDF's page count. Used for cost and context estimates (§5.3) and per-model
 * attachment limits (§4). Neither decodes the file; both give up (return {@code null} / {@code -1})
 * rather than guess when the bytes aren't what they expect.
 */
public final class AttachmentInspector {

    private static final Pattern PAGE_OBJECT = Pattern.compile("/Type\\s*/Page(?![A-Za-z])");
    private static final Pattern PAGE_COUNT = Pattern.compile(
            "/Type\\s*/Pages\\b[^>]*?/Count\\s+(\\d+)|/Count\\s+(\\d+)[^>]*?/Type\\s*/Pages\\b");

    private AttachmentInspector() {
    }

    /**
     * Width and height in pixels of a PNG, JPEG, GIF or WebP image, read from its header, or
     * {@code null} if the bytes aren't one of those formats or the header is truncated.
     */
    public static int[] imageDimensions(byte[] data) {
        if (data == null || data.length < 10) {
            return null;
        }
        try {
            if (startsWith(data, 0x89, 'P', 'N', 'G') && data.length >= 24) {
                return new int[] {int32BigEndian(data, 16), int32BigEndian(data, 20)};
            }
            if (startsWith(data, 'G', 'I', 'F', '8')) {
                return new int[] {uint16LittleEndian(data, 6), uint16LittleEndian(data, 8)};
            }
            if (startsWith(data, 0xFF, 0xD8)) {
                return jpegDimensions(data);
            }
            if (startsWith(data, 'R', 'I', 'F', 'F') && data.length >= 30 && data[8] == 'W' && data[9] == 'E'
                    && data[10] == 'B' && data[11] == 'P') {
                return webpDimensions(data);
            }
        } catch (ArrayIndexOutOfBoundsException truncated) {
            return null;
        }
        return null;
    }

    /**
     * The number of pages in a PDF, counted from its page objects (or its page tree's
     * {@code /Count}), or {@code -1} when neither can be read — typically because the page
     * dictionaries sit in compressed object streams.
     */
    public static int pdfPageCount(byte[] data) {
        if (data == null || data.length == 0) {
            return -1;
        }
        String text = new String(data, StandardCharsets.ISO_8859_1);
        int pageObjects = 0;
        Matcher pages = PAGE_OBJECT.matcher(text);
        while (pages.find()) {
            pageObjects++;
        }
        if (pageObjects > 0) {
            return pageObjects;
        }
        int largestCount = -1;
        Matcher counts = PAGE_COUNT.matcher(text);
        while (counts.find()) {
            String value = counts.group(1) != null ? counts.group(1) : counts.group(2);
            largestCount = Math.max(largestCount, Integer.parseInt(value));
        }
        return largestCount > 0 ? largestCount : -1;
    }

    /** {@link #pdfPageCount}, or a size-based guess (one page per 100 KB, at least one) when the count can't be read. */
    public static int estimatedPdfPages(byte[] data) {
        int counted = pdfPageCount(data);
        if (counted > 0) {
            return counted;
        }
        return Math.max(1, (data == null ? 0 : data.length) / 100_000);
    }

    private static int[] jpegDimensions(byte[] data) {
        int offset = 2;
        while (offset + 9 < data.length) {
            if ((data[offset] & 0xFF) != 0xFF) {
                return null;
            }
            int marker = data[offset + 1] & 0xFF;
            if (marker == 0xFF) {
                offset++; // fill byte
                continue;
            }
            boolean startOfFrame = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (startOfFrame) {
                return new int[] {uint16BigEndian(data, offset + 7), uint16BigEndian(data, offset + 5)};
            }
            offset += 2 + uint16BigEndian(data, offset + 2);
        }
        return null;
    }

    private static int[] webpDimensions(byte[] data) {
        String chunk = new String(data, 12, 4, StandardCharsets.US_ASCII);
        return switch (chunk) {
            case "VP8 " -> new int[] {uint16LittleEndian(data, 26) & 0x3FFF, uint16LittleEndian(data, 28) & 0x3FFF};
            case "VP8L" -> {
                int bits = (data[21] & 0xFF) | (data[22] & 0xFF) << 8 | (data[23] & 0xFF) << 16 | (data[24] & 0xFF) << 24;
                yield new int[] {(bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1};
            }
            case "VP8X" -> new int[] {uint24LittleEndian(data, 24) + 1, uint24LittleEndian(data, 27) + 1};
            default -> null;
        };
    }

    private static boolean startsWith(byte[] data, int... prefix) {
        for (int i = 0; i < prefix.length; i++) {
            if ((data[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static int int32BigEndian(byte[] data, int offset) {
        return (data[offset] & 0xFF) << 24 | (data[offset + 1] & 0xFF) << 16 | (data[offset + 2] & 0xFF) << 8 | (data[offset + 3] & 0xFF);
    }

    private static int uint16BigEndian(byte[] data, int offset) {
        return (data[offset] & 0xFF) << 8 | (data[offset + 1] & 0xFF);
    }

    private static int uint16LittleEndian(byte[] data, int offset) {
        return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8;
    }

    private static int uint24LittleEndian(byte[] data, int offset) {
        return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8 | (data[offset + 2] & 0xFF) << 16;
    }
}
