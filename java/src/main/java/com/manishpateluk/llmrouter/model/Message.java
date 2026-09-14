package com.manishpateluk.llmrouter.model;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/** One prior turn in {@code Request.history} — see {@code LIBRARY_SPEC.md} §3. */
@Value
@Builder
@Jacksonized
public class Message {

    Role role;
    String content;

    public static Message user(String content) {
        return Message.builder().role(Role.USER).content(content).build();
    }

    public static Message assistant(String content) {
        return Message.builder().role(Role.ASSISTANT).content(content).build();
    }

    public static Message system(String content) {
        return Message.builder().role(Role.SYSTEM).content(content).build();
    }

    public static Message tool(String content) {
        return Message.builder().role(Role.TOOL).content(content).build();
    }
}
