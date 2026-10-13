package io.ajdm.engine;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Times only a blocked read; disk backpressure is deliberately excluded. */
final class StallGuard implements AutoCloseable {
    private final InputStream input;
    private final long timeoutNanos;
    private final ScheduledFuture<?> watchdog;
    private volatile long readingSince;
    private volatile boolean stalled;

    StallGuard(InputStream input, Duration timeout, ScheduledExecutorService scheduler) {
        this.input = input; this.timeoutNanos = timeout.toNanos();
        long interval = Math.max(1, Math.min(100, timeout.toMillis() / 4));
        watchdog = scheduler.scheduleAtFixedRate(this::check, interval, interval, TimeUnit.MILLISECONDS);
    }
    private synchronized void check() {
        if (readingSince != 0 && System.nanoTime() - readingSince >= timeoutNanos) {
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
