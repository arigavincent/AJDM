package io.ajdm.engine;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Times only a blocked read; disk backpressure is deliberately excluded. With a peer clock, a read that has waited
 * {@code peerTimeout} while another connection of the same download received bytes is also a stall: the link works,
 * so this flow is dead (typically in TCP retransmission backoff) and a fresh connection resumes its range sooner.
 * A whole-link outage moves no peer bytes and still waits the full timeout.
 */
final class StallGuard implements AutoCloseable {
    private final InputStream input;
    private final long timeoutNanos, peerTimeoutNanos;
    private final java.util.function.LongSupplier peerByte;
    private final ScheduledFuture<?> watchdog;
    private volatile long readingSince;
    private volatile boolean stalled;

    StallGuard(InputStream input, Duration timeout, ScheduledExecutorService scheduler) {
        this(input, timeout, scheduler, null, timeout);
    }
    StallGuard(InputStream input, Duration timeout, ScheduledExecutorService scheduler,
               java.util.function.LongSupplier peerByte, Duration peerTimeout) {
        this.input = input; this.timeoutNanos = timeout.toNanos();
        this.peerByte = peerByte; this.peerTimeoutNanos = Math.min(timeoutNanos, peerTimeout.toNanos());
        long interval = Math.max(1, Math.min(100, Math.min(timeout.toMillis(), peerTimeout.toMillis()) / 4));
        watchdog = scheduler.scheduleAtFixedRate(this::check, interval, interval, TimeUnit.MILLISECONDS);
    }
    private synchronized void check() {
        long since = readingSince, waited = System.nanoTime() - since;
        boolean peersMoved = peerByte != null && waited >= peerTimeoutNanos && peerByte.getAsLong() - since > 0;
        if (since != 0 && (waited >= timeoutNanos || peersMoved)) {
            if (peersMoved && waited < timeoutNanos) EngineTrace.event("peer-stall", -1, String.format(java.util.Locale.ROOT, "%.1fs", waited / 1e9));
            stalled = true;
            readingSince = 0;
            try { input.close(); } catch (IOException ignored) { /* Reader reports timeout. */ }
        }
    }
    int read(byte[] buffer, int length) throws IOException {
        synchronized (this) {
            if (stalled) throw new SocketTimeoutException("Response body stalled");
            readingSince = System.nanoTime();
        }
        try {
            int count = input.read(buffer, 0, length);
            if (stalled) throw new SocketTimeoutException("Response body stalled");
            return count;
        }
        catch (IOException failure) {
            if (stalled) throw new SocketTimeoutException("Response body stalled");
            throw failure;
        } finally {
            synchronized (this) { readingSince = 0; }
        }
    }
    int readOne() throws IOException {
        byte[] one = new byte[1];
        int count = read(one, 1);
        return count < 0 ? -1 : one[0] & 255;
    }
    @Override public void close() { watchdog.cancel(false); }
}
