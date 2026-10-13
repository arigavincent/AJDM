package io.ajdm.engine;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SchedulerCompletionTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void completingTheLastLeaseWakesWaitingObserversBeforeTheirSampleDeadline(boolean adaptive) throws Exception {
        var scheduler = new SegmentScheduler(65536, List.of(), 1, 65536);
        var lease = scheduler.assign(0);
        var returned = new CountDownLatch(2); var failure = new AtomicReference<Throwable>();
        Runnable waiting = () -> {
            try { scheduler.awaitSampleOrCompletion(5000); }
            catch (Throwable error) { failure.set(error); }
            finally { returned.countDown(); }
        };
        Thread observer = Thread.ofVirtual().unstarted(waiting);
        Thread peer = Thread.ofVirtual().unstarted(waiting);
        observer.start(); peer.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while ((observer.getState() != Thread.State.TIMED_WAITING || peer.getState() != Thread.State.TIMED_WAITING)
                    && System.nanoTime() < deadline) Thread.sleep(1);
            assertEquals(Thread.State.TIMED_WAITING, observer.getState(), "Observer must actually wait before completion");
            assertEquals(Thread.State.TIMED_WAITING, peer.getState(), "Peer must actually wait before completion");
            scheduler.acknowledge(scheduler.reserve(lease, 0, 0, 65536));
            if (adaptive) scheduler.release(lease, 0); else scheduler.complete(lease, 0);
            assertTrue(returned.await(1, TimeUnit.SECONDS), "Completion should wake the observer rather than wait five seconds");
            assertNull(failure.get());
            // Completion preceding another observer's wait must not lose the notification.
            observer = Thread.ofVirtual().unstarted(() -> {
                try { scheduler.awaitSampleOrCompletion(5000); }
                catch (Throwable error) { failure.set(error); }
            });
            observer.start(); observer.join(1000);
            assertFalse(observer.isAlive(), "An already completed scheduler must not wait"); assertNull(failure.get());
        } finally { observer.interrupt(); peer.interrupt(); observer.join(1000); peer.join(1000); }
    }
}
