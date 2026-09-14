package com.manishpateluk.llmrouter.model;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * A file the model produced (e.g. via a code-execution or image-generation tool) — see
 * {@code LIBRARY_SPEC.md} §3 (`Response.generatedFiles`). Only ever populated when the model
 * that served the request has {@code ModelEntry.supportsFileOutput} set — see §4.
 *
 * <p>Exactly one of {@code data} (the provider returned the file inline) or {@code url} (the
 * provider returned a reference/temporary download link instead) is populated.
 */
@Value
@Builder
@Jacksonized
public class GeneratedFile {

    String mediaType;
    String filename;
    byte[] data;
    String url;
}
