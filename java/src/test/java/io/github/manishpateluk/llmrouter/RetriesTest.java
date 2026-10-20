package io.github.manishpateluk.llmrouter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class RetriesTest {

    private static final Duration ONE_SECOND = Duration.ofSeconds(1);

    @Test
    void retryDelayDoublesForEachRetryAlreadyMade() {
        assertThat(Retries.delayBeforeRetry(0, ONE_SECOND, null, null)).isEqualTo(Duration.ofSeconds(1));
        assertThat(Retries.delayBeforeRetry(1, ONE_SECOND, null, null)).isEqualTo(Duration.ofSeconds(2));
        assertThat(Retries.delayBeforeRetry(2, ONE_SECOND, null, null)).isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    void retryAfterReplacesTheBackoff() {
        assertThat(Retries.delayBeforeRetry(2, ONE_SECOND, Duration.ofMillis(250), null)).isEqualTo(Duration.ofMillis(250));
        assertThat(Retries.delayBeforeRetry(0, ONE_SECOND, Duration.ZERO, null)).isEqualTo(Duration.ZERO);
    }

    @Test
    void aRetryAfterBeyondTheMaximumMeansFallBack() {
        assertThat(Retries.delayBeforeRetry(0, ONE_SECOND, Retries.MAX_RETRY_AFTER, null)).isEqualTo(Retries.MAX_RETRY_AFTER);
        assertThat(Retries.delayBeforeRetry(0, ONE_SECOND, Retries.MAX_RETRY_AFTER.plusMillis(1), null)).isNull();
    }

    @Test
    void aWaitThatWouldReachTheCallTimeoutMeansFallBack() {
        assertThat(Retries.delayBeforeRetry(0, ONE_SECOND, null, Duration.ofSeconds(2))).isEqualTo(ONE_SECOND);
        assertThat(Retries.delayBeforeRetry(1, ONE_SECOND, null, Duration.ofSeconds(2))).isNull();
        assertThat(Retries.delayBeforeRetry(0, ONE_SECOND, Duration.ofSeconds(3), Duration.ofSeconds(2))).isNull();
    }

    @Test
    void manyRetriesDoNotOverflow() {
        assertThat(Retries.delayBeforeRetry(100, Duration.ofMillis(1), null, null)).isEqualTo(Duration.ofMillis(1L << 20));
    }
}
