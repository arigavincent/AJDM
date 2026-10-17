package io.ajdm.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.jupiter.api.Test;

class StallGuardTest {
    /** Like HttpClient's body stream on JDK 25.0.4: a blocked read waits on a queue that close() does not wake. */
    private static final class ParkedBody extends InputStream {
        final LinkedBlockingQueue<Integer> queue = new LinkedBlockingQueue<>();
        @Override public int read() throws IOException {
            try { return queue.take(); }
            catch (InterruptedException e) { throw new InterruptedIOException("interrupted"); }
        }
        @Override public void close() { /* Does not unblock the reader. */ }
    }

    @Test void aStallIsReportedEvenWhenClosingTheBodyDoesNotWakeTheReader() throws Exception {
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            for (boolean virtual : new boolean[]{false, true}) {
                var outcome = new java.util.concurrent.CompletableFuture<Throwable>();
                Runnable reading = () -> {
                    try (var guard = new StallGuard(new ParkedBody(), Duration.ofMillis(200), scheduler)) {
                        guard.read(new byte[1], 1);
                        outcome.complete(null);
                    } catch (Throwable failure) {
                        // The interrupt used to wake the read must not leak into the worker's later waits.
                        outcome.complete(Thread.currentThread().isInterrupted() ? new AssertionError("interrupt leaked") : failure);
                    }
                };
                if (virtual) Thread.ofVirtual().start(reading); else Thread.ofPlatform().start(reading);
                var failure = outcome.get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertInstanceOf(SocketTimeoutException.class, failure, () -> "virtual=" + virtual + ": " + failure);
            }
        } finally { scheduler.shutdownNow(); }
    }
}
