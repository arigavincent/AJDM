package io.ajdm.bench;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/** Application-level fixture conditions; delay and truncation are not TCP RTT or packet loss. */
public record NetworkProfile(String name, int bytes, long perResponseRate, List<RateStep> sharedRates,
                             Duration responseDelay, Set<Integer> droppedBodies) {
    public record RateStep(Duration after, long bytesPerSecond) {
        public RateStep {
            if (after.isNegative() || after.compareTo(Duration.ofMinutes(5)) > 0 || bytesPerSecond < 1
                    || bytesPerSecond > 1024L * 1024 * 1024) throw new IllegalArgumentException("Invalid rate step");
        }
    }
    public NetworkProfile {
        sharedRates = List.copyOf(sharedRates); droppedBodies = Set.copyOf(droppedBodies);
        if (!name.matches("[a-z0-9][a-z0-9-]{0,47}") || bytes < 1 || bytes > 512 * 1024 * 1024
                || perResponseRate < 0 || perResponseRate > 1024L * 1024 * 1024
                || responseDelay.isNegative() || responseDelay.compareTo(Duration.ofSeconds(5)) > 0
                || droppedBodies.stream().anyMatch(ordinal -> ordinal < 1 || ordinal > 10000)) {
            throw new IllegalArgumentException("Invalid benchmark profile");
        }
        if (!sharedRates.isEmpty() && !sharedRates.getFirst().after().isZero()) throw new IllegalArgumentException("Shared schedule must begin at zero");
        for (int i = 1; i < sharedRates.size(); i++) if (sharedRates.get(i).after().compareTo(sharedRates.get(i - 1).after()) <= 0) {
            throw new IllegalArgumentException("Rate steps must have increasing times");
        }
    }
    /** Zero denotes no shared limit. Time is measured from the first payload request. */
    public long sharedRateAt(long elapsedNanos) {
        long rate = 0;
        for (var step : sharedRates) {
            if (step.after().toNanos() > elapsedNanos) break;
            rate = step.bytesPerSecond();
        }
        return rate;
    }
    public static List<NetworkProfile> catalog() {
        int mib = 1024 * 1024;
        return List.of(
                new NetworkProfile("unthrottled", 8 * mib + 17, 0, List.of(), Duration.ZERO, Set.of()),
                new NetworkProfile("per-response-cap", 8 * mib + 17, 512 * 1024, List.of(), Duration.ZERO, Set.of()),
                new NetworkProfile("shared-cap", 4 * mib + 17, 0, List.of(new RateStep(Duration.ZERO, 512 * 1024)), Duration.ZERO, Set.of()),
                new NetworkProfile("response-delay", 4 * mib + 17, mib, List.of(), Duration.ofMillis(150), Set.of()),
                new NetworkProfile("changing-shared-rate", 8 * mib + 17, 0, List.of(new RateStep(Duration.ZERO, 2L * mib),
                        new RateStep(Duration.ofSeconds(2), 256 * 1024), new RateStep(Duration.ofSeconds(5), 4L * mib)), Duration.ZERO, Set.of()),
                new NetworkProfile("dropped-bodies", 4 * mib + 17, mib, List.of(), Duration.ofMillis(50), Set.of(2, 5)));
    }
    /**
     * Larger operator-run comparison sources for {@code serve-benchmark}, kept out of {@link #catalog()} so the
     * matrix and its tests stay fast. A per-connection cap rewards more connections by design; a shared cap does not.
     */
    public static List<NetworkProfile> comparisons() {
        int mib = 1024 * 1024;
        return List.of(
                new NetworkProfile("capped-host-64m", 64 * mib, 512 * 1024, List.of(), Duration.ZERO, Set.of()),
                new NetworkProfile("home-link-256m", 256 * mib, 0, List.of(new RateStep(Duration.ZERO, 8L * mib)), Duration.ZERO, Set.of()));
    }
    public static NetworkProfile named(String name) {
        return java.util.stream.Stream.concat(catalog().stream(), comparisons().stream()).filter(profile -> profile.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown benchmark profile"));
    }
}
