package io.github.manishpateluk.llmrouter.config;

import java.time.Duration;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * When to stop trying a failing model for a while — see {@code LIBRARY_SPEC.md} §5.5. Once a
 * provider/model has failed {@code failures} attempts in a row (each after its retries), the
 * router skips it on later calls for {@code duration}, recording it as {@code SKIPPED}. When the
 * time is up its failure count starts again from zero and it's tried as normal. A success also
 * resets the count.
 *
 * <p>Set on {@code RouterConfig.cooldown}; {@code null} there (the default) switches cooldown off.
 */
@Value
@Builder
@Jacksonized
public class CooldownPolicy {

    /** Failed attempts in a row that start a cooldown; at least 1. */
    int failures;

    /** How long a model is skipped once cooling down; positive. */
    Duration duration;

    public static CooldownPolicy of(int failures, Duration duration) {
        return CooldownPolicy.builder().failures(failures).duration(duration).build();
    }
}
