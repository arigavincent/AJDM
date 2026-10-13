package io.ajdm.model;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

public record TransferOptions(Duration connectTimeout, Duration requestTimeout, Duration stallTimeout,
                              int bufferBytes, int maxInflightBuffers, long windowBytes,
                              int maxRetries, Duration retryBaseDelay, Duration retryMaxDelay,
                              String expectedSha256, Duration checkpointInterval,
                              int connections, long minSegmentBytes, boolean adaptive, int initialConnections) {
    public TransferOptions {
        positive(connectTimeout); positive(requestTimeout); positive(stallTimeout);
        Objects.requireNonNull(checkpointInterval);
        if (checkpointInterval.compareTo(Duration.ofMillis(10)) < 0 || checkpointInterval.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("Checkpoint interval must be between 10 ms and 30 s");
        }
        Objects.requireNonNull(retryBaseDelay); Objects.requireNonNull(retryMaxDelay);
        if (initialConnections < 1 || initialConnections > 8 || connections < 1 || connections > 32 || minSegmentBytes < 16384 || minSegmentBytes > 64L * 1024 * 1024
                || bufferBytes < 1024 || bufferBytes > 1024 * 1024 || maxInflightBuffers < 1
                || (long) bufferBytes * maxInflightBuffers > 256L * 1024 * 1024
                || windowBytes < 1 || windowBytes > 16L * 1024 * 1024 || maxRetries < 0
                || maxRetries > 100 || retryBaseDelay.isNegative() || retryMaxDelay.isNegative()
                || retryMaxDelay.compareTo(Duration.ofDays(1)) > 0
                || retryBaseDelay.compareTo(retryMaxDelay) > 0) {
            throw new IllegalArgumentException("Invalid transfer limits");
        }
        if (expectedSha256 != null) {
            if (!expectedSha256.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("Expected SHA-256 must contain 64 hex digits");
            }
            expectedSha256 = expectedSha256.toLowerCase(Locale.ROOT);
        }
    }
    /** New adaptive jobs start at four; the store preserves legacy jobs' saved initial count. */
    public TransferOptions(Duration connectTimeout, Duration requestTimeout, Duration stallTimeout,
                           int bufferBytes, int maxInflightBuffers, long windowBytes, int maxRetries,
                           Duration retryBaseDelay, Duration retryMaxDelay, String expectedSha256,
                           Duration checkpointInterval, int connections, long minSegmentBytes, boolean adaptive) {
        this(connectTimeout, requestTimeout, stallTimeout, bufferBytes, maxInflightBuffers, windowBytes,
                maxRetries, retryBaseDelay, retryMaxDelay, expectedSha256, checkpointInterval,
                connections, minSegmentBytes, adaptive, 4);
    }
    public TransferOptions withInitialConnections(int initial) {
        return new TransferOptions(connectTimeout, requestTimeout, stallTimeout, bufferBytes, maxInflightBuffers,
                windowBytes, maxRetries, retryBaseDelay, retryMaxDelay, expectedSha256, checkpointInterval,
                connections, minSegmentBytes, adaptive, initial);
    }
    /** The existing constructor retains fixed parallel behavior. */
    public TransferOptions(Duration connectTimeout, Duration requestTimeout, Duration stallTimeout,
                           int bufferBytes, int maxInflightBuffers, long windowBytes, int maxRetries,
                           Duration retryBaseDelay, Duration retryMaxDelay, String expectedSha256,
                           Duration checkpointInterval, int connections, long minSegmentBytes) {
        this(connectTimeout, requestTimeout, stallTimeout, bufferBytes, maxInflightBuffers, windowBytes,
                maxRetries, retryBaseDelay, retryMaxDelay, expectedSha256, checkpointInterval, connections, minSegmentBytes, false);
    }
    public TransferOptions(Duration connectTimeout, Duration requestTimeout, Duration stallTimeout,
                           int bufferBytes, int maxInflightBuffers, long windowBytes, int maxRetries,
                           Duration retryBaseDelay, Duration retryMaxDelay, String expectedSha256, Duration checkpointInterval) {
        this(connectTimeout, requestTimeout, stallTimeout, bufferBytes, maxInflightBuffers, windowBytes,
                maxRetries, retryBaseDelay, retryMaxDelay, expectedSha256, checkpointInterval, 1, 1024 * 1024);
    }
    public TransferOptions withConnections(int count) {
        return withParallelism(count, minSegmentBytes);
    }
    /** Change an existing job's connection count while retaining its scheduling mode and other options. */
    public TransferOptions withConnectionCount(int count) {
        return new TransferOptions(connectTimeout, requestTimeout, stallTimeout, bufferBytes, maxInflightBuffers,
                windowBytes, maxRetries, retryBaseDelay, retryMaxDelay, expectedSha256, checkpointInterval, count, minSegmentBytes, adaptive, initialConnections);
    }
    public TransferOptions withParallelism(int count, long minimumBytes) {
        return new TransferOptions(connectTimeout, requestTimeout, stallTimeout, bufferBytes, maxInflightBuffers,
                windowBytes, maxRetries, retryBaseDelay, retryMaxDelay, expectedSha256, checkpointInterval, count, minimumBytes, false, initialConnections);
    }
    /** Start at the saved initial count and measure trials up to this maximum. */
    public TransferOptions withAdaptiveParallelism(int maximum, long minimumBytes) {
        return new TransferOptions(connectTimeout, requestTimeout, stallTimeout, bufferBytes, maxInflightBuffers,
                windowBytes, maxRetries, retryBaseDelay, retryMaxDelay, expectedSha256, checkpointInterval, maximum, minimumBytes, true, initialConnections);
    }
    public TransferOptions(Duration connectTimeout, Duration requestTimeout, Duration stallTimeout,
                           int bufferBytes, int maxInflightBuffers, long windowBytes, int maxRetries,
                           Duration retryBaseDelay, Duration retryMaxDelay, String expectedSha256) {
        this(connectTimeout, requestTimeout, stallTimeout, bufferBytes, maxInflightBuffers, windowBytes,
                maxRetries, retryBaseDelay, retryMaxDelay, expectedSha256, Duration.ofSeconds(5));
    }
    private static void positive(Duration value) {
        Objects.requireNonNull(value);
        if (value.isZero() || value.isNegative() || value.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Timeout must be positive and at most one day");
        }
    }
    public static TransferOptions defaults() {
        return new TransferOptions(Duration.ofSeconds(10), Duration.ofSeconds(20), Duration.ofSeconds(15),
                256 * 1024, 256, 4L * 1024 * 1024, 10,
                Duration.ofMillis(500), Duration.ofSeconds(30), null).withParallelism(4, 512 * 1024);
    }
}
