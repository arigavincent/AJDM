package io.ajdm.engine;

/** Attempt-local, bounded diagnostics. Contains no source URLs or request headers. */
public record AdaptationEvent(long elapsedMillis, int connections, String reason, double usefulBytesPerSecond) { }
