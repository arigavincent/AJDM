package io.ajdm.engine;

/**
 * Shared active-response and in-flight-buffer limits across downloads in one engine. {@code prewarm} opens a
 * fixed-mode download's other connections while its probe is in flight.
 */
public record EngineLimits(int connectionsPerHost, int connectionsTotal, int inflightBytes, boolean prewarm) {
    public EngineLimits {
        if (connectionsPerHost < 1 || connectionsPerHost > 32 || connectionsTotal < 1 || connectionsTotal > 256
                || inflightBytes < 1024 * 1024 || inflightBytes > 256 * 1024 * 1024) throw new IllegalArgumentException("Invalid engine resource limits");
    }
    public EngineLimits(int connectionsPerHost, int connectionsTotal, int inflightBytes) {
        this(connectionsPerHost, connectionsTotal, inflightBytes, true);
    }
    public static EngineLimits defaults() { return new EngineLimits(16, 64, 64 * 1024 * 1024); }
    /** Same limits with connection warm-up off, for callers that must see exactly one metadata request. */
    public EngineLimits withoutPrewarm() { return new EngineLimits(connectionsPerHost, connectionsTotal, inflightBytes, false); }
}
