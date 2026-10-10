package io.github.jhonatasdefreitas.notifications.domain;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * How long to wait after each failed attempt. The list also sets the limit: three intervals
 * mean four attempts, the first one plus three retries.
 */
public record RetryPolicy(List<Duration> intervals) {

    public RetryPolicy {
        if (intervals.stream().anyMatch(interval -> interval.isNegative() || interval.isZero())) {
            throw new IllegalArgumentException("Retry intervals must be positive: " + intervals);
        }
        intervals = List.copyOf(intervals);
    }

    public int maxAttempts() {
        return intervals.size() + 1;
    }

    /** Empty when the failed attempt was the last one allowed. */
    public Optional<Duration> delayAfter(int failedAttempt) {
        return failedAttempt <= intervals.size() ? Optional.of(intervals.get(failedAttempt - 1)) : Optional.empty();
    }
}
