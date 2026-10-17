package io.ajdm.engine;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Times only a blocked read; disk backpressure is deliberately excluded. With a {@link PeerStalls} group, a read
 * that the group judges individually silent is also a stall, so a fresh connection resumes its range sooner than
 * the full timeout. A whole-link slowdown or outage leaves the full timeout in charge.
 */
final class StallGuard implements AutoCloseable {
    private final InputStream input;
    private final long timeoutNanos;
    private final PeerStalls peers;
    private final ScheduledFuture<?> watchdog;
    private volatile long readingSince;
    private volatile boolean stalled;
    private Thread reader;

    StallGuard(InputStream input, Duration timeout, ScheduledExecutorService scheduler) {
        this(input, timeout, scheduler, null);
    }
    StallGuard(InputStream input, Duration timeout, ScheduledExecutorService scheduler, PeerStalls peers) {
        this.input = input; this.timeoutNanos = timeout.toNanos();
        this.peers = peers != null && peers.timeoutNanos() < timeoutNanos ? peers : null;
        if (this.peers != null) this.peers.open(this, System.nanoTime());
        long checkMillis = this.peers == null ? timeout.toMillis() : this.peers.timeoutNanos() / 1_000_000;
        long interval = Math.max(1, Math.min(100, checkMillis / 4));
        watchdog = scheduler.scheduleAtFixedRate(this::check, interval, interval, TimeUnit.MILLISECONDS);
    }
    private synchronized void check() {
        long since = readingSince;
        if (since == 0) return;
        long now = System.nanoTime(), waited = now - since;
        boolean alone = waited < timeoutNanos && peers != null && peers.abandon(this, since, now);
        if (waited >= timeoutNanos || alone) {
            if (alone) EngineTrace.event("peer-stall", -1, String.format(java.util.Locale.ROOT, "%.1fs", waited / 1e9));
            stalled = true;
            readingSince = 0;
            try { input.close(); } catch (IOException ignored) { /* Reader reports timeout. */ }
            // Closing does not always wake a blocked read (HttpClient's body stream on JDK 25.0.4 stays parked in its
            // queue), so the reader is also interrupted; it clears the interrupt itself before reporting the stall.
            if (reader != null) reader.interrupt();
        }
    }
    int read(byte[] buffer, int length) throws IOException {
        synchronized (this) {
            if (stalled) throw new SocketTimeoutException("Response body stalled");
            readingSince = System.nanoTime();
            reader = Thread.currentThread();
        }
        try {
            int count = input.read(buffer, 0, length);
            if (stalled) throw new SocketTimeoutException("Response body stalled");
            if (count > 0 && peers != null) peers.received(this, System.nanoTime());
            return count;
        }
        catch (IOException failure) {
            if (stalled) throw new SocketTimeoutException("Response body stalled");
            throw failure;
        } finally {
            synchronized (this) {
                readingSince = 0; reader = null;
                if (stalled) Thread.interrupted();
            }
        }
    }
    int readOne() throws IOException {
        byte[] one = new byte[1];
        int count = read(one, 1);
        return count < 0 ? -1 : one[0] & 255;
    }
    @Override public void close() {
        watchdog.cancel(false);
        if (peers != null) peers.close(this);
    }
}
