package com.manishpateluk.llmrouter.model;

import java.util.List;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * One prior turn in {@code Request.history} — see {@code LIBRARY_SPEC.md} §3.
 *
 * <p>{@code toolCalls} (on an {@code ASSISTANT} message) and {@code toolCallId} (on a {@code
 * TOOL} message) are optional correlation fields: when present, a provider adapter sends this
 * turn using the provider's native tool-call/tool-result wire format instead of flattening it
 * into a plain text turn. Both default to "absent" ({@code toolCalls} empty, {@code toolCallId}
 * {@code null}), which keeps every existing caller's behavior — and the flattened wire
 * representation — unchanged.
 */
@Value
@Builder
@Jacksonized
public class Message {

    Role role;
    String content;

    @Builder.Default
    List<ToolCall> toolCalls = List.of();

    String toolCallId;

    public static Message user(String content) {
        return Message.builder().role(Role.USER).content(content).build();
    }

    public static Message assistant(String content) {
        return Message.builder().role(Role.ASSISTANT).content(content).build();
    }

    /** An assistant turn that requested {@code toolCalls} — see the class-level doc. */
    public static Message assistant(String content, List<ToolCall> toolCalls) {
        return Message.builder()
                .role(Role.ASSISTANT)
                .content(content)
                .toolCalls(toolCalls == null ? List.of() : toolCalls)
                .build();
    }

    public static Message system(String content) {
        return Message.builder().role(Role.SYSTEM).content(content).build();
    }

    public static Message tool(String content) {
        return Message.builder().role(Role.TOOL).content(content).build();
    }

    /** A tool result correlated to the call it answers — see the class-level doc. */
    public static Message tool(String toolCallId, String content) {
        return Message.builder().role(Role.TOOL).content(content).toolCallId(toolCallId).build();
    }
}
