package io.ajdm.engine;

import io.ajdm.model.DownloadException;
import io.ajdm.net.CancellationScope;
import java.util.function.LongSupplier;

/**
 * One shared speed limit for every transfer of an engine (ENG-11): bytes are paid for as they arrive, and a connection
 * that runs ahead waits. A quarter-second burst keeps short reads smooth; 0 means unlimited. Waiting happens in short
 * sleeps so pause and cancel stay prompt.
 */
public final class RateLimiter {
    private static final long BURST_NANOS = 250_000_000L, SLICE_NANOS = 50_000_000L;
    private final LongSupplier clock;
    private volatile long bytesPerSecond;
    private long theoreticalArrival;

    public RateLimiter() { this(System::nanoTime); }
    RateLimiter(LongSupplier clock) { this.clock = clock; theoreticalArrival = clock.getAsLong(); }

    public long limit() { return bytesPerSecond; }
    public void limit(long bytesPerSecond) {
        if (bytesPerSecond < 0) throw new IllegalArgumentException("Negative limit");
        this.bytesPerSecond = bytesPerSecond;
    }

    /** Charges {@code bytes} and returns how long the caller must wait before reading more, in nanoseconds. */
    synchronized long reserve(long bytes) {
        long rate = bytesPerSecond, now = clock.getAsLong();
        if (rate <= 0 || bytes <= 0) { theoreticalArrival = now; return 0; }
        long start = Math.max(theoreticalArrival, now); // Idle time is never banked beyond the burst tolerance.
        theoreticalArrival = start + (long) (bytes * 1e9 / rate);
        return Math.max(0, theoreticalArrival - now - BURST_NANOS);
    }

    void acquire(long bytes, CancellationScope scope) throws InterruptedException, DownloadException {
        long wait = reserve(bytes);
        while (wait > 0 && bytesPerSecond > 0) { // Lifting the limit also releases connections already waiting.
            if (scope != null) scope.check();
            long slice = Math.min(wait, SLICE_NANOS);
            Thread.sleep(slice / 1_000_000, (int) (slice % 1_000_000));
            wait -= slice;
        }
    }
}
