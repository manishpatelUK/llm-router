package io.github.manishpateluk.llmrouter.provider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Helpers for {@link ProviderAdapter#isTransient} and {@link ProviderAdapter#retryAfter} — see
 * {@code LIBRARY_SPEC.md} §5.5. Shared so every adapter treats the same HTTP statuses as worth a
 * retry and reads {@code Retry-After} the same way.
 */
public final class TransientFailures {

    /**
     * Statuses worth retrying on the same model: request timeout, rate limit, server errors, and
     * Anthropic's 529 "overloaded".
     */
    private static final Set<Integer> TRANSIENT_STATUSES = Set.of(408, 429, 500, 502, 503, 504, 529);

    private TransientFailures() {
    }

    public static boolean isTransientStatus(int statusCode) {
        return TRANSIENT_STATUSES.contains(statusCode);
    }

    /** Whether {@code failure}, or anything that caused it, is an I/O error such as a dropped connection. */
    public static boolean isIoFailure(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof IOException || t instanceof UncheckedIOException) {
                return true;
            }
        }
        return false;
    }

    /** The first {@code type} in {@code failure}'s cause chain (including itself), or {@code null}. */
    public static <T extends Throwable> T find(Throwable failure, Class<T> type) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
        }
        return null;
    }

    /**
     * The wait a provider asked for, from {@code retry-after-ms} (milliseconds, sent by OpenAI) or
     * {@code Retry-After} (seconds, or an HTTP date), or {@code null} when neither is present or
     * readable. {@code header} returns a header's values by name, case-insensitively, or an empty
     * list (or {@code null}).
     */
    public static Duration retryAfter(Function<String, List<String>> header) {
        String millis = first(header.apply("retry-after-ms"));
        if (millis != null) {
            try {
                return nonNegative(Duration.ofMillis((long) Double.parseDouble(millis)));
            } catch (NumberFormatException e) {
                // fall through to Retry-After
            }
        }
        String value = first(header.apply("retry-after"));
        if (value == null) {
            return null;
        }
        try {
            return nonNegative(Duration.ofMillis((long) (Double.parseDouble(value) * 1000)));
        } catch (NumberFormatException e) {
            try {
                Instant at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return nonNegative(Duration.between(Instant.now(), at));
            } catch (DateTimeParseException unreadable) {
                return null;
            }
        }
    }

    private static String first(List<String> values) {
        return values == null || values.isEmpty() || values.get(0).isBlank() ? null : values.get(0).trim();
    }

    private static Duration nonNegative(Duration duration) {
        return duration.isNegative() ? Duration.ZERO : duration;
    }
}
