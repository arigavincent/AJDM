package io.ajdm.engine;

import io.ajdm.model.ByteRange;
import io.ajdm.model.RangeSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicIntegerArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(180)
class SegmentSchedulerTest {
    @Test void wrongOwnerOutOfBoundsAndUnsettledReleaseAreRejected() {
        var scheduler = new SegmentScheduler(100, List.of(), 2, 10);
        var lease = scheduler.assign(0);
        assertThrows(IllegalStateException.class, () -> scheduler.reserve(lease, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> scheduler.reserve(lease, 0, 0, 101));
        var ticket = scheduler.reserve(lease, 0, 0, 100);
        assertThrows(IllegalStateException.class, () -> scheduler.complete(lease, 0));
        assertThrows(IllegalStateException.class, () -> scheduler.rewind(lease, 0));
        scheduler.acknowledge(ticket); scheduler.complete(lease, 0);
        assertThrows(IllegalStateException.class, () -> scheduler.acknowledge(ticket));
        assertThrows(IllegalStateException.class, () -> scheduler.reserve(lease, 0, 100, 1));
    }
    @Test void queuedBytesRemainUnfinishedUntilTheirWriteAcknowledgement() {
        var scheduler = new SegmentScheduler(200000, List.of(new ByteRange(0, 1000)), 2, 16384);
        var lease = scheduler.assign(0);
        var ticket = scheduler.reserve(lease, 0, 1000, 4096);
        assertEquals(1000, new RangeSet(scheduler.completed()).coveredBytes());
        assertEquals(199000, new RangeSet(scheduler.unfinished()).coveredBytes());
        scheduler.acknowledge(ticket);
        assertEquals(5096, new RangeSet(scheduler.completed()).coveredBytes());
        assertEquals(194904, new RangeSet(scheduler.unfinished()).coveredBytes());
    }
    @Test void concurrentRandomizedLeasesWriteEachPendingByteExactlyOnce() throws Exception {
        var random = new Random(95333);
        for (int trial = 0; trial < runs(); trial++) {
            int trialSeed = trial;
            int total = 2000 + random.nextInt(2000);
            var saved = List.of(new ByteRange(13, 97), new ByteRange(total / 2, total / 2 + 21));
            var scheduler = new SegmentScheduler(total, saved, 8, 32);
            var touched = new AtomicIntegerArray(total);
            for (var range : saved) for (long offset = range.start(); offset < range.endExclusive(); offset++) touched.set((int) offset, 1);
            try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
                for (int owner = 0; owner < scheduler.workers(); owner++) {
                    int workerId = owner;
                    tasks.add(workers.submit(() -> {
                        var interleaving = new Random(workerId + 95333L * trialSeed);
                        SegmentScheduler.Lease lease;
                        while ((lease = scheduler.assign(workerId)) != null) {
                            while (scheduler.position(lease) < lease.end()) {
                                assertFalse(Thread.currentThread().isInterrupted(), "Lease trial interrupted");
                                long offset = scheduler.position(lease);
                                int length = (int) Math.min(1 + interleaving.nextInt(71), lease.end() - offset);
                                var ticket = scheduler.reserve(lease, workerId, offset, length);
                                Thread.yield();
                                for (int index = (int) offset; index < offset + length; index++) assertEquals(1, touched.incrementAndGet(index));
                                scheduler.acknowledge(ticket);
                            }
                            scheduler.complete(lease, workerId);
                        }
                    }));
                }
                try { for (var task : tasks) task.get(); }
                finally { tasks.forEach(task -> task.cancel(true)); }
            }
            for (int offset = 0; offset < total; offset++) assertEquals(1, touched.get(offset));
            assertEquals(List.of(new ByteRange(0, total)), scheduler.completed());
            assertEquals(List.of(), scheduler.unfinished());
        }
    }
    @Test void stealingCutsAnActiveWindowButNeverReservedBytes() {
        var scheduler = new SegmentScheduler(1024 * 1024, List.of(), 2, 16384, 1);
        var victim = scheduler.assign(0);
        var window = scheduler.beginWindow(victim, 0, 1024 * 1024);
        assertEquals(new ByteRange(0, 1024 * 1024), window);
        var ticket = scheduler.reserve(victim, 0, 0, 4096);
        var thief = scheduler.steal(1, new double[]{1000, 3000});
        assertNotNull(thief);
        assertTrue(thief.start() < window.endExclusive(), "The active window was not split");
        assertTrue(thief.start() >= 4096 + 16384, "The cut must leave reserved bytes plus a margin");
        assertEquals(thief.start(), victim.end());
        assertEquals(thief.start(), scheduler.windowEnd(victim));
        assertPartition(scheduler, 1024 * 1024);
        assertThrows(IllegalStateException.class, () -> scheduler.release(victim, 0));
        scheduler.acknowledge(ticket);
        // A read that raced the steal is clamped to the shortened window; the excess is discarded.
        int wanted = (int) (victim.end() - 4096) + 8192;
        var rest = scheduler.reserveUpTo(victim, 0, 4096, wanted);
        assertEquals(victim.end() - 4096, rest.length());
        assertNull(scheduler.reserveUpTo(victim, 0, victim.end(), 1));
        scheduler.acknowledge(rest); scheduler.finishWindow(victim, 0);
        scheduler.release(victim, 0);
        assertPartition(scheduler, 1024 * 1024);
        assertEquals(1024 * 1024 - thief.start(), new RangeSet(scheduler.unfinished()).coveredBytes());
    }
    @Test void aRetriedWindowCanBeCutAndItsOwnerKeepsOnlyTheLowerPart() {
        var scheduler = new SegmentScheduler(131072, List.of(), 2, 16384, 1);
        var victim = scheduler.assign(0);
        scheduler.beginWindow(victim, 0, 131072);
        var ticket = scheduler.reserve(victim, 0, 0, 4096);
        scheduler.acknowledge(ticket); scheduler.rewind(victim, 0);
        var thief = scheduler.steal(1, new double[]{1000, 3000});
        assertNotNull(thief);
        assertEquals(thief.start(), scheduler.windowEnd(victim));
        assertTrue(thief.start() >= 16384);
        assertPartition(scheduler, 131072);
    }
    @Test void stealingLeavesATooSmallActiveWindowAlone() {
        var scheduler = new SegmentScheduler(65536, List.of(), 2, 16384, 1);
        var victim = scheduler.assign(0);
        scheduler.beginWindow(victim, 0, 65536);
        scheduler.reserve(victim, 0, 0, 32768);
        assertNull(scheduler.steal(1, new double[]{1000, 3000}));
        assertEquals(65536, scheduler.windowEnd(victim));
    }
    @Test void aRejectedLeaseShedsItsRemainderButTheLastActiveLeaseNeverSheds() {
        int total = 1024 * 1024;
        var scheduler = new SegmentScheduler(total, List.of(), 2, 16384);
        var first = scheduler.assign(0); var second = scheduler.assign(1);
        scheduler.beginWindow(second, 1, total);
        var ticket = scheduler.reserve(second, 1, second.start(), 4096);
        assertFalse(scheduler.shed(second, 1), "Unsettled writes must settle before shedding");
        scheduler.acknowledge(ticket);
        assertTrue(scheduler.shed(second, 1));
        assertThrows(IllegalStateException.class, () -> scheduler.position(second));
        assertPartition(scheduler, total);
        // The shed remainder is queued for any worker; the acknowledged bytes stay completed.
        assertEquals(4096, new RangeSet(scheduler.completed()).coveredBytes());
        var resumed = scheduler.assign(1);
        assertEquals(second.start() + 4096, resumed.start());
        scheduler.release(resumed, 1);
        scheduler.beginWindow(first, 0, 65536);
        assertFalse(scheduler.shed(first, 0), "The last active lease keeps retrying instead of stalling the job");
        assertEquals(65536, scheduler.windowEnd(first));
    }
    @Test void speedWeightedStealingChoosesTheLargestCompletionTime() {
        var scheduler = new SegmentScheduler(12 * 65536, List.of(), 3, 16384, 2);
        var first = scheduler.assign(0); var second = scheduler.assign(1);
        scheduler.beginWindow(first, 0, 65536); scheduler.beginWindow(second, 1, 65536);
        var thief = scheduler.steal(2, new double[]{1000, 10000, 2000});
        assertNotNull(thief);
        assertTrue(thief.start() < second.start());
        assertEquals(thief.start(), first.end());
        assertEquals(12 * 65536, scheduler.unfinished().stream().mapToLong(r -> r.endExclusive() - r.start()).sum());
    }
    @Test void randomizedConcurrentWindowsAndStealsCoverEachByteOnce() throws Exception {
        for (int trial = 0; trial < runs(); trial++) {
            int trialSeed = trial;
            int total = 131072 + (trial % 100) * 173;
            var scheduler = new SegmentScheduler(total, List.of(), 4, 512, 2);
            scheduler.target(4);
            var touched = new AtomicIntegerArray(total);
            try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
                for (int owner = 0; owner < 4; owner++) {
                    int workerId = owner;
                    tasks.add(workers.submit(() -> {
                        var random = new Random(workerId + 763L + 31L * trialSeed);
                        while (!scheduler.done()) {
                            assertFalse(Thread.currentThread().isInterrupted(), "Steal trial interrupted");
                            var lease = scheduler.next(workerId, new double[]{100, 2000, 3000, 500});
                            if (lease == null) { Thread.yield(); continue; }
                            while (scheduler.position(lease) < lease.end()) {
                                var window = scheduler.beginWindow(lease, workerId, 1024 + random.nextInt(8192));
                                while (scheduler.position(lease) < scheduler.windowEnd(lease)) {
                                    assertFalse(Thread.currentThread().isInterrupted(), "Window trial interrupted");
                                    long offset = scheduler.position(lease);
                                    long previousEnd = lease.end();
                                    assertTrue(scheduler.windowEnd(lease) <= window.endExclusive(), "Window grew");
                                    // Model a network read sized before a concurrent steal may shorten the window.
                                    int count = (int) Math.min(1 + random.nextInt(1024), window.endExclusive() - offset);
                                    Thread.yield();
                                    var ticket = scheduler.reserveUpTo(lease, workerId, offset, count);
                                    if (ticket == null) break;
                                    count = (int) ticket.length(); Thread.yield();
                                    assertTrue(offset + count <= lease.end(), "Write exceeds the current lease end");
                                    for (int index = (int) offset; index < offset + count; index++) assertEquals(1, touched.incrementAndGet(index));
                                    scheduler.acknowledge(ticket);
                                    assertEquals(offset + count, scheduler.position(lease));
                                    assertTrue(lease.end() <= previousEnd, "Lease end grew during a handoff");
                                    if (random.nextInt(100) == 0) assertPartition(scheduler, total);
                                }
                                scheduler.finishWindow(lease, workerId);
                            }
                            scheduler.release(lease, workerId);
                        }
                    }));
                }
                try { for (var task : tasks) task.get(); }
                finally { tasks.forEach(task -> task.cancel(true)); }
            }
            for (int offset = 0; offset < total; offset++) assertEquals(1, touched.get(offset));
            assertEquals(List.of(new ByteRange(0, total)), scheduler.completed()); assertTrue(scheduler.unfinished().isEmpty());
        }
    }
    @Test void hugeAndEmptyRepresentationsPartitionWithoutOverflow() {
        var empty = new SegmentScheduler(0, List.of(), 4, 16384);
        assertEquals(0, empty.workers()); assertTrue(empty.unfinished().isEmpty());
        var huge = new SegmentScheduler(Long.MAX_VALUE, List.of(), 32, 16384);
        assertEquals(Long.MAX_VALUE, new RangeSet(huge.unfinished()).coveredBytes());
        long sum = huge.unfinished().stream().mapToLong(range -> range.endExclusive() - range.start()).sum();
        assertEquals(Long.MAX_VALUE, sum);
        assertThrows(IllegalArgumentException.class, () -> new SegmentScheduler(10, List.of(), 0, 1));
    }
    @Test void laggardSplitCutsTheInflightWindowAboveReservedBytes() {
        int total = 1024 * 1024;
        var scheduler = new SegmentScheduler(total, List.of(), 2, 16384, 1);
        var victim = scheduler.assign(0);
        var window = scheduler.beginWindow(victim, 0, total);
        var ticket = scheduler.reserve(victim, 0, 0, 4096);
        assertTrue(scheduler.splitLaggard(0));
        scheduler.target(2);
        var next = scheduler.assign(1);
        assertNotNull(next);
        assertTrue(next.start() < window.endExclusive());
        assertTrue(next.start() >= 4096 + 16384);
        assertEquals(victim.end(), next.start());
        assertEquals(next.start(), scheduler.windowEnd(victim));
        assertEquals(0, next.start() % 65536);
        assertPartition(scheduler, total);
        scheduler.acknowledge(ticket);
        assertPartition(scheduler, total);
        assertFalse(scheduler.splitLaggard(99));
    }
    @Test void laggardSplitLeavesASmallRemainderWhole() {
        var scheduler = new SegmentScheduler(65536, List.of(), 2, 16384, 1);
        var victim = scheduler.assign(0);
        scheduler.beginWindow(victim, 0, 65536);
        assertFalse(scheduler.splitLaggard(0));
        assertPartition(scheduler, 65536);
    }
    private static int runs() {
        int count = Integer.getInteger("ajdm.schedulerRuns", 100);
        if (count < 1 || count > 10000) throw new IllegalArgumentException("schedulerRuns must be 1..10000");
        return count;
    }
    private static void assertPartition(SegmentScheduler scheduler, long total) {
        // Read one consistent snapshot while other owners continue to interleave outside it.
        synchronized (scheduler) {
            var all = new java.util.ArrayList<>(scheduler.completed());
            all.addAll(scheduler.unfinished()); all.sort(java.util.Comparator.comparingLong(ByteRange::start));
            long position = 0;
            for (var range : all) {
                assertEquals(position, range.start(), "Overlapping ownership or a missing byte");
                position = range.endExclusive();
            }
            assertEquals(total, position);
        }
    }
}
