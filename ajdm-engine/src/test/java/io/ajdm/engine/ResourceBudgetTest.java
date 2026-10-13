package io.ajdm.engine;

import io.ajdm.net.CancellationScope;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResourceBudgetTest {
    @Test void blockedRequestCancellationReturnsTheHostPermitAndCloseIsIdempotent() throws Exception {
        var budget = new ConnectionBudget(new EngineLimits(1, 1, 1024 * 1024));
        var scope = new CancellationScope();
        var first = budget.acquire(URI.create("https://first.example/file"), new CancellationScope());
        CountDownLatch waiting = new CountDownLatch(1), ended = new CountDownLatch(1);
        var interrupted = new java.util.concurrent.atomic.AtomicReference<Exception>();
        Thread worker = Thread.ofVirtual().unstarted(() -> {
            waiting.countDown();
            try (var lease = budget.acquire(URI.create("https://second.example/file"), scope)) { assertNotNull(lease); }
            catch (Exception expected) { interrupted.set(expected); }
            finally { ended.countDown(); }
        });
        scope.worker(worker); worker.start(); assertTrue(waiting.await(1, TimeUnit.SECONDS));
        scope.cancel(); assertTrue(ended.await(1, TimeUnit.SECONDS)); worker.join();
        assertNotNull(interrupted.get(), "Canceled wait must not acquire a response lease");
        first.close(); first.close();
        try (var lease = budget.acquire(URI.create("https://second.example/other"), new CancellationScope())) { assertNotNull(lease); }
    }
    @Test void sharedBufferLimitAppliesAcrossWritersAndReturnsOnInterruption() throws Exception {
        var budget = new BufferBudget(4096);
        budget.acquire(2049); // rounded to three KiB
        CountDownLatch waiting = new CountDownLatch(1), acquired = new CountDownLatch(1);
        Thread worker = Thread.ofVirtual().start(() -> {
            waiting.countDown();
            try { budget.acquire(2048); acquired.countDown(); budget.release(2048); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertTrue(waiting.await(1, TimeUnit.SECONDS)); assertFalse(acquired.await(50, TimeUnit.MILLISECONDS));
        budget.release(2049); assertTrue(acquired.await(1, TimeUnit.SECONDS)); worker.join();
        budget.acquire(4096); budget.release(4096);
    }
}
