package io.github.manishpateluk.llmrouter;

import java.nio.charset.StandardCharsets;

import io.github.manishpateluk.llmrouter.model.Attachment;

/** Small, real attachment bytes for tests: a PNG header of a given size and a PDF with a given page count. */
public final class TestMedia {

    private TestMedia() {
    }

    /** A PNG signature and IHDR chunk declaring {@code width × height} — enough for header-based sizing. */
    public static byte[] png(int width, int height) {
        byte[] data = new byte[33];
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
        System.arraycopy(signature, 0, data, 0, signature.length);
        writeInt(data, 16, width);
        writeInt(data, 20, height);
        data[24] = 8; // bit depth
        data[25] = 6; // RGBA
        return data;
    }

    /** An uncompressed PDF skeleton with {@code pages} page objects, like a single deck page export. */
    public static byte[] pdf(int pages) {
        StringBuilder pdf = new StringBuilder("%PDF-1.7\n1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < pages; i++) {
            kids.append(3 + i).append(" 0 R ");
        }
        pdf.append("2 0 obj\n<< /Type /Pages /Kids [").append(kids).append("] /Count ").append(pages).append(" >>\nendobj\n");
        for (int i = 0; i < pages; i++) {
            pdf.append(3 + i).append(" 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 960 540] >>\nendobj\n");
        }
        pdf.append("%%EOF\n");
        return pdf.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    public static Attachment pngAttachment() {
        return Attachment.builder().mediaType("image/png").data(png(1280, 720)).filename("chart.png").build();
    }

    public static Attachment deckPagePdf() {
        return Attachment.builder().mediaType("application/pdf").data(pdf(1)).filename("deck-page-3.pdf").build();
    }

    private static void writeInt(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >>> 24);
        data[offset + 1] = (byte) (value >>> 16);
        data[offset + 2] = (byte) (value >>> 8);
        data[offset + 3] = (byte) value;
    }
}
