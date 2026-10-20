package io.github.manishpateluk.llmrouter;

import java.time.Duration;

/** How long to wait before retrying a transient failure on the same model (§5.5). */
final class Retries {

    /**
     * The longest {@code Retry-After} the router waits for. A provider asking for longer is
     * treated as unavailable for now: the router falls back to the next candidate instead.
     */
    static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(30);

    private Retries() {
    }

    /**
     * The wait before the next retry, or {@code null} to stop retrying and fall back. That's the
     * provider's {@code retryAfter} when it gave one (up to {@link #MAX_RETRY_AFTER}), otherwise
     * {@code retryDelay} doubled for each retry already made; either way only if it ends before
     * the call timeout ({@code remaining}, {@code null} for none).
     *
     * @param retriesSoFar retries already made on this candidate
     */
    static Duration delayBeforeRetry(int retriesSoFar, Duration retryDelay, Duration retryAfter, Duration remaining) {
        Duration delay;
        if (retryAfter != null) {
            if (retryAfter.compareTo(MAX_RETRY_AFTER) > 0) {
                return null;
            }
            delay = retryAfter;
        } else {
            delay = retryDelay.multipliedBy(1L << Math.min(retriesSoFar, 20));
        }
        if (remaining != null && delay.compareTo(remaining) >= 0) {
            return null;
        }
        return delay;
    }
}
