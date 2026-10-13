package io.ajdm.engine;

import io.ajdm.net.CancellationScope;
import io.ajdm.net.RequestBudget;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

final class ConnectionBudget implements RequestBudget {
    private final ConcurrentHashMap<String, Semaphore> hosts = new ConcurrentHashMap<>();
    private final Semaphore global;
    private final int perHost;
    ConnectionBudget(EngineLimits limits) { perHost = limits.connectionsPerHost(); global = new Semaphore(limits.connectionsTotal(), true); }
    @Override public Lease acquire(URI uri, CancellationScope cancellation) throws IOException, InterruptedException {
        cancellation.check();
        Semaphore host = hosts.computeIfAbsent(uri.getHost().toLowerCase(Locale.ROOT), key -> new Semaphore(perHost, true));
        host.acquire();
        boolean acquired = false;
        try {
            global.acquire(); acquired = true; cancellation.check();
            var released = new AtomicBoolean();
            return () -> { if (released.compareAndSet(false, true)) { global.release(); host.release(); } };
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (acquired) global.release(); host.release(); throw e;
        }
    }
}
