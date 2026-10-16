package io.ajdm.engine;

import io.ajdm.model.ByteRange;
import io.ajdm.model.RangeSet;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Exclusive ranges. Reserved (queued or written) bytes are never reassigned. Like a classic download manager,
 * an idle worker may cut into another worker's active request window beyond a small margin; the victim then
 * stops reading at its new end and abandons the rest of that response.
 */
final class SegmentScheduler {
    private static final long CUT_MARGIN = 65536;
    private final ArrayDeque<ByteRange> pending = new ArrayDeque<>();
    private final Map<Integer, Lease> active = new HashMap<>();
    private final RangeSet completed;
    private final int workers;
    private final long minimumBytes;
    private int target;
    private final long[] acknowledged;

    SegmentScheduler(long total, List<ByteRange> saved, int connections, long minimumBytes) {
        this(total, saved, connections, minimumBytes, connections);
    }
    SegmentScheduler(long total, List<ByteRange> saved, int connections, long minimumBytes, int initial) {
        if (initial < 1 || initial > connections) throw new IllegalArgumentException("Invalid initial workers");
        this.minimumBytes = minimumBytes;
        if (connections < 1 || connections > 32 || minimumBytes < 1) throw new IllegalArgumentException("Invalid segment limits");
        completed = new RangeSet(saved);
        List<ByteRange> gaps = completed.gaps(total);
        long remaining = total - completed.coveredBytes();
        workers = remaining == 0 ? 0 : (int) Math.min(connections, Math.max(1, remaining / minimumBytes));
        acknowledged = new long[workers]; target = Math.min(initial, workers);
        if (workers == 0) return;
        long share = remaining / target + (remaining % target == 0 ? 0 : 1);
        long alignment = 65536, extra = (alignment - share % alignment) % alignment;
        long size = share > Long.MAX_VALUE - extra ? Long.MAX_VALUE : Math.max(minimumBytes, share + extra);
        for (ByteRange gap : gaps) {
            long start = gap.start();
            while (start < gap.endExclusive()) {
                long end = start + Math.min(size, gap.endExclusive() - start);
                pending.add(new ByteRange(start, end)); start = end;
            }
        }
    }
    int workers() { return workers; }
    synchronized Lease assign(int owner) {
        if (owner < 0 || owner >= workers || active.containsKey(owner)) throw new IllegalStateException("Worker already owns a segment or is invalid");
        ByteRange range = pending.poll();
        if (range == null) return null;
        var lease = new Lease(owner, range); active.put(owner, lease); return lease;
    }
    synchronized Ticket reserve(Lease lease, int owner, long offset, int length) {
        requireOwner(lease, owner);
        if (length <= 0 || offset != lease.reserved || length > (lease.windowActive ? lease.protectedEnd : lease.end) - offset) {
            throw new IllegalArgumentException("Write outside its segment or reserved sequence");
        }
        lease.reserved += length;
        return new Ticket(lease, new ByteRange(offset, offset + length));
    }
    /** Reserve up to {@code length} bytes, clamped to a window that a concurrent steal may have shortened; null if none remain. */
    synchronized Ticket reserveUpTo(Lease lease, int owner, long offset, int length) {
        requireOwner(lease, owner);
        long limit = lease.windowActive ? lease.protectedEnd : lease.end;
        if (length <= 0 || offset != lease.reserved || offset > limit) throw new IllegalArgumentException("Write outside its segment or reserved sequence");
        int allowed = (int) Math.min(length, limit - offset);
        return allowed == 0 ? null : reserve(lease, owner, offset, allowed);
    }
    /** Current exclusive end of the active window; it only shrinks while the window is active. */
    synchronized long windowEnd(Lease lease) {
        requireOwner(lease, lease.owner);
        return lease.windowActive ? lease.protectedEnd : lease.end;
    }
    synchronized void acknowledge(Ticket ticket) {
        Lease lease = ticket.lease;
        requireOwner(lease, lease.owner);
        if (ticket.used || ticket.range.start() != lease.written) throw new IllegalStateException("Write acknowledgement is stale or out of order");
        ticket.used = true; lease.written = ticket.range.endExclusive();
        acknowledged[lease.owner] += ticket.range.endExclusive() - ticket.range.start();
        completed.add(ticket.range.start(), ticket.range.endExclusive());
    }
    synchronized long position(Lease lease) { requireOwner(lease, lease.owner); return lease.written; }
    synchronized void rewind(Lease lease, long offset) {
        requireOwner(lease, lease.owner);
        if (lease.reserved != lease.written || offset < lease.range.start() || offset > lease.written) {
            throw new IllegalStateException("Cannot rewind unsettled writes");
        }
        completed.remove(offset, lease.written); lease.written = offset; lease.reserved = offset;
    }
    synchronized void complete(Lease lease, int owner) {
        requireOwner(lease, owner);
        if (lease.written != lease.end || lease.reserved != lease.written) throw new IllegalStateException("Segment still has unfinished writes");
        active.remove(owner);
        if (done()) notifyAll();
    }
    synchronized ByteRange beginWindow(Lease lease, int owner, long maximum) {
        requireOwner(lease, owner);
        if (maximum < 1 || lease.windowActive || lease.written != lease.reserved || lease.written >= lease.end) {
            throw new IllegalStateException("Window cannot start with unsettled ownership");
        }
        lease.windowActive = true;
        lease.protectedEnd = lease.written + Math.min(maximum, lease.end - lease.written);
        return new ByteRange(lease.written, lease.protectedEnd);
    }
    synchronized void finishWindow(Lease lease, int owner) {
        requireOwner(lease, owner);
        if (!lease.windowActive || lease.written != lease.protectedEnd || lease.reserved != lease.written) {
            throw new IllegalStateException("Window is not validated and settled");
        }
        lease.windowActive = false; lease.protectedEnd = lease.written;
    }
    synchronized void release(Lease lease, int owner) {
        requireOwner(lease, owner);
        if (lease.windowActive || lease.reserved != lease.written) throw new IllegalStateException("Cannot hand off an unsettled window");
        if (lease.written < lease.end) pending.addFirst(new ByteRange(lease.written, lease.end));
        active.remove(owner);
        if (done()) notifyAll();
    }
    /**
     * Give a server-rejected lease back while another worker still holds work, so a host connection cap sheds
     * the extra connection instead of stalling its bytes. Reserved bytes stay completed or owned; the unfinished
     * remainder returns to the queue. The last active worker never sheds.
     */
    synchronized boolean shed(Lease lease, int owner) {
        requireOwner(lease, owner);
        if (active.size() <= 1 || lease.reserved != lease.written) return false;
        lease.windowActive = false; lease.protectedEnd = lease.written;
        release(lease, owner);
        notifyAll();
        return true;
    }
    synchronized Lease next(int owner, double[] speeds) {
        if (owner >= target) return null;
        Lease next = assign(owner);
        return next == null ? steal(owner, speeds) : next;
    }
    synchronized Lease steal(int owner, double[] speeds) {
        if (owner < 0 || owner >= workers || active.containsKey(owner)) throw new IllegalStateException("Invalid thief");
        Lease victim = null; double largestEta = -1;
        for (Lease candidate : active.values()) {
            long floor = floor(candidate);
            long tail = candidate.end - floor;
            if (tail < minimumBytes || tail - minimumBytes < minimumBytes) continue;
            double eta = (candidate.end - candidate.written) / speed(speeds, candidate.owner);
            if (eta > largestEta) { largestEta = eta; victim = candidate; }
        }
        if (victim == null) return null;
        long floor = floor(victim), tail = victim.end - floor;
        double thiefSpeed = speed(speeds, owner), victimSpeed = speed(speeds, victim.owner);
        double share = Math.max(.1, Math.min(.9, thiefSpeed / (thiefSpeed + victimSpeed)));
        long stolen = Math.max(minimumBytes, Math.min(tail - minimumBytes, (long) (tail * share)));
        long split = victim.end - stolen;
        long aligned = split - split % 65536;
        if (aligned >= floor + minimumBytes && victim.end - aligned >= minimumBytes) split = aligned;
        var result = new Lease(owner, new ByteRange(split, victim.end));
        result.stolen = true; cut(victim, split); active.put(owner, result); return result;
    }
    /** Split a laggard's upper unreserved half, cutting its active window if needed; queued writes stay owned. */
    synchronized boolean splitLaggard(int owner) {
        Lease victim = active.get(owner);
        if (victim == null || victim.end - victim.written <= minimumBytes * 4) return false;
        long floor = floor(victim), tail = victim.end - floor;
        if (tail < minimumBytes * 2) return false;
        long split = floor + tail / 2;
        split -= split % 65536;
        if (split - floor < minimumBytes || victim.end - split < minimumBytes) return false;
        pending.addFirst(new ByteRange(split, victim.end)); cut(victim, split); notifyAll(); return true;
    }
    /** Lowest offset another worker may take: past reserved bytes plus a margin for bytes already being read. */
    private long floor(Lease lease) {
        long margin = lease.windowActive ? Math.min(minimumBytes, CUT_MARGIN) : 0;
        return Math.min(lease.end, lease.reserved + margin);
    }
    private static void cut(Lease victim, long split) {
        victim.end = split;
        if (victim.windowActive && victim.protectedEnd > split) victim.protectedEnd = split;
    }
    private static double speed(double[] speeds, int owner) {
        if (owner < speeds.length && speeds[owner] > 0) return Math.max(1024, speeds[owner]);
        double sum = 0; int count = 0;
        for (double rate : speeds) if (rate > 0) { sum += rate; count++; }
        return count == 0 ? 128 * 1024 : Math.max(1024, sum / count);
    }
    synchronized void target(int value) { target = Math.max(1, Math.min(workers, value)); }
    synchronized boolean admitted(int owner) { return owner < target; }
    synchronized boolean done() { return pending.isEmpty() && active.isEmpty(); }
    /** Preserve the sample interval while allowing completed work to wake every observer immediately. */
    synchronized void awaitSampleOrCompletion(long timeoutMillis) throws InterruptedException {
        if (timeoutMillis < 1 || timeoutMillis > 60000) throw new IllegalArgumentException("Invalid sample interval");
        long remaining = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        long deadline = System.nanoTime() + remaining;
        while (!done() && remaining > 0) {
            java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(this, remaining);
            remaining = deadline - System.nanoTime();
        }
    }
    synchronized boolean canSplit() {
        if (!pending.isEmpty()) return true;
        return active.values().stream().anyMatch(l -> l.end - floor(l) >= minimumBytes * 2);
    }
    synchronized Measurement measurement() {
        long backlog = 0;
        boolean[] busy = new boolean[workers];
        for (Lease lease : active.values()) { backlog += lease.reserved - lease.written; busy[lease.owner] = true; }
        return new Measurement(acknowledged.clone(), busy, backlog);
    }
    record Measurement(long[] acknowledged, boolean[] busy, long queuedBytes) { }
    synchronized List<ByteRange> completed() { return completed.ranges(); }
    synchronized List<ByteRange> unfinished() {
        var result = new java.util.ArrayList<>(pending);
        for (Lease lease : active.values()) if (lease.written < lease.end) result.add(new ByteRange(lease.written, lease.end));
        return List.copyOf(result);
    }
    private void requireOwner(Lease lease, int owner) {
        if (lease.owner != owner || active.get(owner) != lease) throw new IllegalStateException("Segment lease is no longer owned by this worker");
    }
    static final class Lease {
        private final int owner;
        private final ByteRange range;
        private long reserved, written, protectedEnd;
        private volatile long end;
        private boolean windowActive, stolen;
        private Lease(int owner, ByteRange range) { this.owner = owner; this.range = range; reserved = range.start(); written = range.start(); protectedEnd = range.start(); end = range.endExclusive(); }
        boolean stolen() { return stolen; }
        long start() { return range.start(); }
        long end() { return end; }
    }
    static final class Ticket {
        private final Lease lease;
        private final ByteRange range;
        private boolean used;
        private Ticket(Lease lease, ByteRange range) { this.lease = lease; this.range = range; }
        long length() { return range.endExclusive() - range.start(); }
    }
}
