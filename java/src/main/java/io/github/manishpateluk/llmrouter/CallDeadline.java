package io.github.manishpateluk.llmrouter;

import java.time.Duration;

/**
 * One call's time limits, from {@code RouterConfig.callTimeout} and {@code attemptTimeout} (§5.5).
 * Measured on {@link System#nanoTime()}, so wall-clock changes don't move it.
 */
final class CallDeadline {

    /** How long one attempt may run, and what its failure says when it runs out. */
    record AttemptLimit(Duration bound, String timeoutReason) {
    }

    private final Duration callTimeout;
    private final long deadlineNanos;

    private CallDeadline(Duration callTimeout) {
        this.callTimeout = callTimeout;
        this.deadlineNanos = callTimeout == null ? 0 : System.nanoTime() + callTimeout.toNanos();
    }

    /** Starts the clock on a call; {@code callTimeout} may be {@code null} for no limit. */
    static CallDeadline start(Duration callTimeout) {
        return new CallDeadline(callTimeout);
    }

    /** Time left before the call timeout, or {@code null} when there's none. Never negative. */
    Duration remaining() {
        if (callTimeout == null) {
            return null;
        }
        return Duration.ofNanos(Math.max(0, deadlineNanos - System.nanoTime()));
    }

    boolean expired() {
        Duration remaining = remaining();
        return remaining != null && remaining.isZero();
    }

    Duration callTimeout() {
        return callTimeout;
    }

    /**
     * The limit on the next attempt: the shorter of {@code attemptTimeout} and the time left on the
     * call, or {@code null} when neither is set, in which case the attempt runs as it always has.
     */
    AttemptLimit limitFor(Duration attemptTimeout) {
        Duration remaining = remaining();
        if (remaining == null && attemptTimeout == null) {
            return null;
        }
        if (remaining != null && (attemptTimeout == null || remaining.compareTo(attemptTimeout) < 0)) {
            return new AttemptLimit(remaining.isZero() ? Duration.ofMillis(1) : remaining,
                    "call timed out: callTimeout of " + format(callTimeout) + " reached");
        }
        return new AttemptLimit(attemptTimeout, "attempt timed out after " + format(attemptTimeout));
    }

    static String format(Duration duration) {
        return duration.toMillis() % 1000 == 0 ? duration.toSeconds() + "s" : duration.toMillis() + "ms";
    }
}
