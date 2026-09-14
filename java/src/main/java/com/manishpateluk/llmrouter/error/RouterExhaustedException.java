package com.manishpateluk.llmrouter.error;

import java.util.List;

import com.manishpateluk.llmrouter.model.AttemptRecord;

/**
 * Raised when every candidate in the resolved route failed or was skipped — see
 * {@code LIBRARY_SPEC.md} §5.1.1. Carries the full {@link #attempts()} list so the caller can
 * see exactly what was tried and why each attempt didn't succeed.
 */
public final class RouterExhaustedException extends LlmRouterException {

    private final List<AttemptRecord> attempts;

    public RouterExhaustedException(List<AttemptRecord> attempts) {
        super(ErrorCode.ROUTER_EXHAUSTED, buildMessage(attempts));
        this.attempts = List.copyOf(attempts);
    }

    /** Every candidate tried (or skipped), in attempt order. */
    public List<AttemptRecord> attempts() {
        return attempts;
    }

    private static String buildMessage(List<AttemptRecord> attempts) {
        StringBuilder message = new StringBuilder("Router exhausted — every candidate failed or was skipped:");
        for (AttemptRecord attempt : attempts) {
            message.append("\n  - ")
                    .append(attempt.getProvider())
                    .append('/')
                    .append(attempt.getModel())
                    .append(" -> ")
                    .append(attempt.getOutcome());
            if (attempt.getReason() != null) {
                message.append(" (").append(attempt.getReason()).append(')');
            }
        }
        return message.toString();
    }
}
