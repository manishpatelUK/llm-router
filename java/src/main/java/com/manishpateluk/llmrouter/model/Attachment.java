package com.manishpateluk.llmrouter.model;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * A file included with a prompt (a document, an image, etc.) — see {@code LIBRARY_SPEC.md} §3.
 *
 * <p>{@code data} is always raw bytes; base64 encoding is purely a wire-format detail internal
 * to each provider adapter, never exposed here. Whether an attachment can actually be sent to a
 * given model is governed by {@code ModelEntry.supportsVision} (for {@code image/*} media types)
 * or {@code ModelEntry.supportsFileInput} (everything else) — see §4.
 */
@Value
@Builder
@Jacksonized
public class Attachment {

    /** MIME type, e.g. {@code "application/pdf"}, {@code "image/png"}. */
    String mediaType;

    byte[] data;

    /** Optional display name. */
    String filename;
}
