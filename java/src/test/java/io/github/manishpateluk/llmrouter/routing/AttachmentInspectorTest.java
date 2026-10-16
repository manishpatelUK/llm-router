package io.github.manishpateluk.llmrouter.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import io.github.manishpateluk.llmrouter.TestMedia;

import org.junit.jupiter.api.Test;

class AttachmentInspectorTest {

    @Test
    void readsPngDimensions() {
        assertThat(AttachmentInspector.imageDimensions(TestMedia.png(1280, 720))).containsExactly(1280, 720);
    }

    @Test
    void readsGifDimensions() {
        byte[] gif = {'G', 'I', 'F', '8', '9', 'a', (byte) 0x40, 0x01, (byte) 0xF0, 0x00, 0, 0, 0};
        assertThat(AttachmentInspector.imageDimensions(gif)).containsExactly(320, 240);
    }

    @Test
    void readsJpegDimensionsFromItsStartOfFrameSegment() {
        byte[] jpeg = {
                (byte) 0xFF, (byte) 0xD8,
                (byte) 0xFF, (byte) 0xE0, 0x00, 0x04, 0x00, 0x00, // APP0 segment, length 4
                (byte) 0xFF, (byte) 0xC0, 0x00, 0x11, 0x08, 0x02, 0x58, 0x03, 0x20, 0x03, 0, 0, 0, 0};
        assertThat(AttachmentInspector.imageDimensions(jpeg)).containsExactly(800, 600);
    }

    @Test
    void readsExtendedWebpDimensions() {
        byte[] webp = new byte[30];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, webp, 0, 4);
        System.arraycopy("WEBPVP8X".getBytes(StandardCharsets.US_ASCII), 0, webp, 8, 8);
        webp[24] = (byte) 0xFF; // width - 1 = 1023 (24-bit little-endian)
        webp[25] = 0x03;
        webp[27] = (byte) 0xFF; // height - 1 = 767
        webp[28] = 0x02;
        assertThat(AttachmentInspector.imageDimensions(webp)).containsExactly(1024, 768);
    }

    @Test
    void unknownOrTruncatedBytesHaveNoDimensions() {
        assertThat(AttachmentInspector.imageDimensions(new byte[] {1, 2, 3})).isNull();
        assertThat(AttachmentInspector.imageDimensions("not an image at all".getBytes(StandardCharsets.US_ASCII))).isNull();
        assertThat(AttachmentInspector.imageDimensions(null)).isNull();
    }

    @Test
    void countsPdfPageObjectsWithoutCountingThePageTree() {
        assertThat(AttachmentInspector.pdfPageCount(TestMedia.pdf(1))).isEqualTo(1);
        assertThat(AttachmentInspector.pdfPageCount(TestMedia.pdf(12))).isEqualTo(12);
    }

    @Test
    void fallsBackToThePageTreeCountWhenPageObjectsArentVisible() {
        byte[] pdf = "%PDF-1.7 2 0 obj << /Type /Pages /Kids [] /Count 37 >> endobj".getBytes(StandardCharsets.ISO_8859_1);
        assertThat(AttachmentInspector.pdfPageCount(pdf)).isEqualTo(37);
    }

    @Test
    void anUnreadablePdfHasNoCountButStillGetsASizeBasedEstimate() {
        byte[] compressed = new byte[350_000];
        assertThat(AttachmentInspector.pdfPageCount(compressed)).isEqualTo(-1);
        assertThat(AttachmentInspector.estimatedPdfPages(compressed)).isEqualTo(3);
        assertThat(AttachmentInspector.estimatedPdfPages(new byte[10])).isEqualTo(1);
    }
}
