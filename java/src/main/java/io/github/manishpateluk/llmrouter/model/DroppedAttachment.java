package io.github.manishpateluk.llmrouter.model;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * One attachment capability negotiation left out of an attempt — see {@code LIBRARY_SPEC.md} §4.
 * {@code index} is its position in the caller's {@code Request.attachments}.
 */
@Value
@Builder
@Jacksonized
public class DroppedAttachment {

    int index;
    String mediaType;
    String filename;

    /** Why it was dropped, e.g. {@code "model does not accept application/pdf"}. */
    String reason;
}
