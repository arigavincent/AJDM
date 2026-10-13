package io.ajdm.engine;

import io.ajdm.model.DownloadException;
import io.ajdm.model.ErrorCode;
import io.ajdm.model.ByteRange;
import io.ajdm.model.RangeSet;
import java.util.List;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/** Multiple producers, ordered disk jobs. Coverage includes only acknowledged positional writes. */
final class BoundedFileWriter {
    private final FileChannel channel;
    private final Executor executor;
    private final int bufferBytes;
    private final Semaphore slots;
    private final ArrayBlockingQueue<byte[]> recycled;
    private final AtomicLong written;
    private final AtomicLong queuedBytes = new AtomicLong();
    private final RangeSet coverage;
    private final BufferBudget budget;
    private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
    private long allocatedLength = -1;

    BoundedFileWriter(FileChannel channel, Executor executor, int bufferBytes, int buffers, AtomicLong written) {
        this(channel, executor, bufferBytes, buffers, written, Checkpoints.prefix(written.get()), new BufferBudget(256 * 1024 * 1024));
    }
    BoundedFileWriter(FileChannel channel, Executor executor, int bufferBytes, int buffers, AtomicLong written,
                      List<ByteRange> saved, BufferBudget budget) {
        this.channel = channel; this.executor = executor; this.bufferBytes = bufferBytes;
        this.slots = new Semaphore(buffers); this.recycled = new ArrayBlockingQueue<>(buffers); this.written = written;
        coverage = new RangeSet(saved); this.budget = budget;
    }
    byte[] acquire() throws InterruptedException, DownloadException {
        slots.acquire();
        try { budget.acquire(bufferBytes); }
        catch (InterruptedException e) { slots.release(); throw e; }
        try { checkFailure(); }
        catch (DownloadException e) { budget.release(bufferBytes); slots.release(); throw e; }
        byte[] buffer = recycled.poll();
        return buffer != null ? buffer : new byte[bufferBytes];
    }
    void release(byte[] buffer) { recycled.add(buffer); budget.release(bufferBytes); slots.release(); }
    void submit(byte[] buffer, int count, long offset) { submit(buffer, count, offset, () -> {}); }
    synchronized void submit(byte[] buffer, int count, long offset, Runnable acknowledged) {
        queuedBytes.addAndGet(count);
        tail = tail.handleAsync((ignored, failure) -> {
            try {
                if (failure != null) throw new CompletionException(failure);
                writeFully(channel, ByteBuffer.wrap(buffer, 0, count), offset);
                synchronized (coverage) { coverage.add(offset, offset + count); written.set(coverage.coveredBytes()); }
                acknowledged.run();
                return null;
            } catch (IOException e) { throw new CompletionException(e); }
            finally { queuedBytes.addAndGet(-count); release(buffer); }
        }, executor);
    }
    long queuedBytes() { return queuedBytes.get(); }
    void drain() throws InterruptedException, DownloadException {
        try { snapshot().get(); }
        catch (ExecutionException e) { throw diskFailure(e); }
    }
    /** Cleanup must not close the channel while platform-thread writes are still in flight. */
    void drainUninterruptibly() {
        CompletableFuture<Void> pending = snapshot();
        boolean interrupted = Thread.interrupted();
        for (;;) {
            try { pending.get(); break; }
            catch (InterruptedException e) { interrupted = true; }
            catch (ExecutionException e) { break; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
    void reset() throws IOException, InterruptedException {
        rewind(0);
    }
    /** Logical extension only; unwritten extents are never added to coverage. */
    synchronized void preallocate(long length) throws IOException, InterruptedException {
        if (length < -1) throw new IllegalArgumentException("Invalid file length");
        allocatedLength = length;
        tail = tail.thenRunAsync(() -> {
            try {
                if (length >= 0) channel.truncate(length);
                extend(channel, length); channel.force(true);
            }
            catch (IOException e) { throw new CompletionException(e); }
        }, executor);
        drain();
    }
    static void extend(FileChannel channel, long length) throws IOException {
        if (length > channel.size()) writeFully(channel, ByteBuffer.wrap(new byte[1]), length - 1);
    }
    void rewind(long offset) throws IOException, InterruptedException {
        drain();
        synchronized (this) {
            tail = CompletableFuture.runAsync(() -> {
                try {
                    channel.truncate(offset);
                    extend(channel, allocatedLength);
                    synchronized (coverage) { coverage.remove(offset, Long.MAX_VALUE); written.set(coverage.coveredBytes()); }
                }
                catch (IOException e) { throw new CompletionException(e); }
            }, executor);
        }
        drain();
    }
    /** Later submits are ordered after this barrier and excluded from the returned checkpoint. */
    synchronized CompletableFuture<List<ByteRange>> flush(java.util.UUID id, CheckpointObserver observer) {
        CompletableFuture<List<ByteRange>> checkpoint = tail.handleAsync((ignored, failure) -> {
            if (failure != null) throw new CompletionException(failure);
            try {
                List<ByteRange> ranges = coverage();
                long boundary = new RangeSet(ranges).coveredBytes();
                observer.at(CheckpointObserver.Phase.BEFORE_FILE_FORCE, id, boundary);
                channel.force(true);
                observer.at(CheckpointObserver.Phase.AFTER_FILE_FORCE, id, boundary);
                return ranges;
            } catch (IOException e) { throw new CompletionException(e); }
        }, executor);
        tail = checkpoint.thenApply(ignored -> null);
        return checkpoint;
    }
    List<ByteRange> coverage() { synchronized (coverage) { return coverage.ranges(); } }
    synchronized void discardRange(long start, long end) {
        tail = tail.thenRunAsync(() -> {
            synchronized (coverage) { coverage.remove(start, end); written.set(coverage.coveredBytes()); }
        }, executor);
    }
    private synchronized CompletableFuture<Void> snapshot() { return tail; }
    private void checkFailure() throws DownloadException {
        CompletableFuture<Void> pending = snapshot();
        if (pending.isCompletedExceptionally()) {
            try { pending.join(); } catch (CompletionException e) { throw diskFailure(e); }
        }
    }
    private static DownloadException diskFailure(Throwable cause) {
        return new DownloadException(ErrorCode.DISK_WRITE_ERROR, "Could not write the partial file", cause);
    }
    static void writeFully(FileChannel channel, ByteBuffer buffer, long offset) throws IOException {
        writeFully(channel::write, buffer, offset);
    }
    @FunctionalInterface interface PositionalSink {
        int write(ByteBuffer buffer, long offset) throws IOException;
    }
    static void writeFully(PositionalSink sink, ByteBuffer buffer, long offset) throws IOException {
        while (buffer.hasRemaining()) {
            int count = sink.write(buffer, offset);
            if (count < 0) throw new IOException("Unexpected end of file channel");
            if (count == 0) { Thread.onSpinWait(); continue; }
            offset += count;
        }
    }
}
