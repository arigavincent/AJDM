package io.ajdm.engine;

/** Shared active-response and in-flight-buffer limits across downloads in one engine. */
public record EngineLimits(int connectionsPerHost, int connectionsTotal, int inflightBytes) {
    public EngineLimits {
        if (connectionsPerHost < 1 || connectionsPerHost > 32 || connectionsTotal < 1 || connectionsTotal > 256
                || inflightBytes < 1024 * 1024 || inflightBytes > 256 * 1024 * 1024) throw new IllegalArgumentException("Invalid engine resource limits");
    }
    public static EngineLimits defaults() { return new EngineLimits(16, 64, 64 * 1024 * 1024); }
}
