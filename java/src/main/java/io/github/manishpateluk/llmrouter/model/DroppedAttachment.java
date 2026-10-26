package io.github.manishpateluk.llmrouter.model;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * One attachment capability negotiation left out of an attempt — see {@code LIBRARY_SPEC.md} §4.
 * {@code index} is its position in {@code Request.everyAttachment()}: the attachments on history
 * messages, in order, then {@code Request.attachments} — so with none in history, its position in
 * {@code Request.attachments}.
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
