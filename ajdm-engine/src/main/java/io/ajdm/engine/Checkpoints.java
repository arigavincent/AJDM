package io.ajdm.engine;

import io.ajdm.model.*;
import io.ajdm.store.DownloadStore;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Serializes file flush -> ledger commit, and coverage invalidation -> truncate. */
final class Checkpoints {
    private final UUID id;
    private final DownloadStore store;
    private final BoundedFileWriter writer;
    private final AtomicLong durable;
    private final CheckpointObserver observer;
    private volatile PrefixHasher hasher;
    private final ScheduledFuture<?> timer;
    private boolean stopped;

    Checkpoints(UUID id, DownloadStore store, BoundedFileWriter writer, AtomicLong durable, Duration interval,
                ScheduledExecutorService scheduler, CheckpointObserver observer, Consumer<DownloadException> onFailure) {
        this.id = id; this.store = store; this.writer = writer; this.durable = durable; this.observer = observer;
        timer = scheduler.scheduleWithFixedDelay(() -> {
            synchronized (this) {
                if (stopped) return;
                try { flush(DownloadState.DOWNLOADING); }
                catch (IOException e) {
                    stopped = true;
                    onFailure.accept(e instanceof DownloadException failure ? failure
                            : new DownloadException(ErrorCode.DISK_WRITE_ERROR, "Checkpoint flush failed", e));
                }
            }
        }, interval.toNanos(), interval.toNanos(), TimeUnit.NANOSECONDS);
    }
    synchronized long flush(DownloadState state) throws IOException {
        return flush(state, null);
    }
    /** Receives the committed contiguous prefix after every flush and an invalidation before every reduction. */
    synchronized void hasher(PrefixHasher hasher) { this.hasher = hasher; }
    synchronized long flush(DownloadState state, ErrorCode error) throws IOException {
        List<ByteRange> ranges = await(writer.flush(id, observer));
        var saved = new RangeSet(ranges);
        long boundary = saved.coveredBytes();
        store.checkpoint(id, ranges, state, error);
        durable.set(boundary);
        observer.at(CheckpointObserver.Phase.AFTER_DB_COMMIT, id, boundary);
        // Forced by writer.flush and committed above: only now may these bytes be hashed.
        if (hasher != null) hasher.advance(saved.prefixLength());
        return boundary;
    }
    synchronized void discardRange(long start, long end, DownloadState state) throws IOException {
        if (hasher != null) hasher.invalidateFrom(start);
        // Reduce the durable claim before reducing acknowledged coverage or replaying this window.
        var retained = new RangeSet(store.get(id).completed());
        retained.remove(start, end);
        store.checkpoint(id, retained.ranges(), state);
        durable.set(retained.coveredBytes());
        writer.discardRange(start, end);
        flush(state);
    }
    synchronized void rewind(long offset, DownloadState state) throws IOException, InterruptedException {
        // Commit the smaller claim first: a crash during truncate must never leave stale coverage.
        invalidate(Math.min(offset, durable.get()), state);
        writer.rewind(offset);
    }
    synchronized void invalidate(long boundary, DownloadState state) throws IOException {
        if (hasher != null) hasher.invalidateFrom(boundary);
        store.checkpoint(id, prefix(boundary), state);
        durable.set(boundary);
    }
    void stop() {
        timer.cancel(false);
        synchronized (this) { stopped = true; }
    }
    static List<ByteRange> prefix(long boundary) { return boundary == 0 ? List.of() : List.of(new ByteRange(0, boundary)); }
    private static <T> T await(CompletableFuture<T> job) throws IOException {
        boolean interrupted = Thread.interrupted();
        try {
            for (;;) {
                try { return job.get(); }
                catch (InterruptedException e) { interrupted = true; }
                catch (ExecutionException e) { throw new DownloadException(ErrorCode.DISK_WRITE_ERROR, "Checkpoint flush failed", e.getCause()); }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
