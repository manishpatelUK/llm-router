package io.github.manishpateluk.llmrouter.model;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * A source the model's answer draws on, such as a web page found by a provider's built-in search
 * — see {@code LIBRARY_SPEC.md} §3 ({@code Response.citations}). Provider-neutral: each adapter
 * maps its own citation format onto this shape.
 */
@Value
@Builder
@Jacksonized
public class Citation {

    /** The source's address. Always present. */
    String url;

    /** The source's title, when the provider gives one; otherwise {@code null}. */
    String title;

    /** A short excerpt from the source, when the provider gives one; otherwise {@code null}. */
    String snippet;
}
