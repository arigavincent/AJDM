package io.ajdm.engine;

import java.time.Duration;
import java.util.Objects;

/** Limits are per running transfer attempt, including initial-probe refreshes. */
public record SourceRefreshPolicy(int maximumRefreshes, Duration timeout) {
    public SourceRefreshPolicy {
        Objects.requireNonNull(timeout);
        if (maximumRefreshes < 1 || maximumRefreshes > 10 || timeout.compareTo(Duration.ofMillis(10)) < 0
                || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Refresh needs 1–10 attempts and a 10 ms–5 minute callback timeout");
        }
    }
    public static SourceRefreshPolicy defaults() { return new SourceRefreshPolicy(3, Duration.ofSeconds(30)); }
}
