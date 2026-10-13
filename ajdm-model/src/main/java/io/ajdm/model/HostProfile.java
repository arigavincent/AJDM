package io.ajdm.model;

/** Network-scoped hint, never proof of source identity or range support. */
public record HostProfile(int connections, double bytesPerSecond, long learnedAtMillis) {
    public HostProfile {
        if (connections < 1 || connections > 32 || !Double.isFinite(bytesPerSecond) || bytesPerSecond <= 0 || learnedAtMillis < 0) {
            throw new IllegalArgumentException("Invalid host hint");
        }
    }
}
