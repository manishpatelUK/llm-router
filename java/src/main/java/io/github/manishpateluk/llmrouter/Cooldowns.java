package io.github.manishpateluk.llmrouter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.manishpateluk.llmrouter.config.CooldownPolicy;
import io.github.manishpateluk.llmrouter.provider.Provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Each provider/model's run of failed attempts, for {@code RouterConfig.cooldown} (§5.5). Lives as
 * long as its {@link LlmRouter}, so a model that keeps failing is skipped across calls, not only
 * within one. Thread-safe.
 */
final class Cooldowns {

    private static final Logger log = LoggerFactory.getLogger(Cooldowns.class);

    /** Failures in a row so far, and when the cooldown they started ends ({@code null} while still under the threshold). */
    private record State(int failures, Instant coolingUntil) {
    }

    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Clock clock;

    Cooldowns(Clock clock) {
        this.clock = clock;
    }

    /**
     * Why {@code provider}/{@code model} must be skipped because it's cooling down, or {@code null}
     * if it can be tried. A cooldown that has run out is forgotten here, so its failure count
     * starts again from zero.
     */
    String coolingDownReason(Provider provider, String model) {
        String key = key(provider, model);
        State state = states.get(key);
        if (state == null || state.coolingUntil() == null) {
            return null;
        }
        Instant now = clock.instant();
        if (!now.isBefore(state.coolingUntil())) {
            states.remove(key, state);
            log.info("Cooldown over for {}/{}; trying it again", provider, model);
            return null;
        }
        long seconds = Math.max(1, Duration.between(now, state.coolingUntil()).toSeconds());
        return "cooling down for another " + seconds + "s after " + state.failures() + " failed attempt(s) in a row";
    }

    /** Counts one failed attempt, starting a cooldown once {@code policy.failures} are in a row. */
    void recordFailure(Provider provider, String model, CooldownPolicy policy) {
        Instant now = clock.instant();
        states.compute(key(provider, model), (key, state) -> {
            boolean expired = state != null && state.coolingUntil() != null && !now.isBefore(state.coolingUntil());
            if (state != null && state.coolingUntil() != null && !expired) {
                // a call that started before the cooldown failed too; the cooldown already running stands
                return new State(state.failures() + 1, state.coolingUntil());
            }
            int failures = state == null || expired ? 1 : state.failures() + 1;
            if (failures < policy.getFailures()) {
                return new State(failures, null);
            }
            log.warn("{}/{} failed {} attempt(s) in a row; skipping it for {}", provider, model, failures, policy.getDuration());
            return new State(failures, now.plus(policy.getDuration()));
        });
    }

    /** A success ends the run of failures. */
    void recordSuccess(Provider provider, String model) {
        states.remove(key(provider, model));
    }

    private static String key(Provider provider, String model) {
        return provider + "/" + model;
    }
}
