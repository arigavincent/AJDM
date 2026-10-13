package io.ajdm.engine;

import java.util.concurrent.Semaphore;

/** KiB rounding bounds acquired buffers even when callers choose non-aligned sizes. */
final class BufferBudget {
    private final Semaphore bytes;
    BufferBudget(int limit) { bytes = new Semaphore(limit / 1024, true); }
    void acquire(int size) throws InterruptedException { bytes.acquire(units(size)); }
    void release(int size) { bytes.release(units(size)); }
    private static int units(int size) { return (size + 1023) / 1024; }
}
