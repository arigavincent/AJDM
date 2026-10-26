package io.ajdm.engine;

import io.ajdm.net.CancellationScope;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 of the contiguous saved prefix, computed while the transfer runs so verification after the last byte reads
 * only the unhashed tail. On a CPU without SHA instructions a 256 MiB file spent 3.0-3.2 s hashing after its last byte;
 * with this 1.0-1.4 s. SHA-256 is strictly sequential, so with N parallel slices finishing together little more than
 * the first slice is hashed early, but that early work also warms the hashing code, roughly doubling the tail's rate.
 * Single-stream downloads hash almost everything during the transfer.
 * <p>
 * Only bytes that a checkpoint has forced and committed are hashed ({@link #advance}). Every reduction of saved coverage
 * calls {@link #invalidateFrom} first, which discards the digest if it reached that far; a chunk read concurrently with
 * an invalidation is dropped by a generation check. Reads use a separate read-only channel that is never interrupted,
 * so the writer's channel cannot be closed by it. The caller re-hashes the whole file before reporting a mismatch.
 */
final class PrefixHasher implements AutoCloseable {
    private static final int CHUNK = 1024 * 1024;
    private final FileChannel channel;
    private final Thread worker;
    private MessageDigest digest = newDigest();
    private long hashed, safe, generation;
    private boolean closed, failed;

    PrefixHasher(Path partial) throws IOException {
        channel = FileChannel.open(partial, StandardOpenOption.READ);
        worker = Thread.ofVirtual().name("ajdm-prefix-hash").start(this::run);
    }

    /** Saved bytes [0, end) are forced and committed; never call with bytes a checkpoint has not made durable. */
    synchronized void advance(long end) {
        if (end > safe) { safe = end; notifyAll(); }
    }
    /** Called before saved coverage from {@code offset} is reduced or rewritten. */
    synchronized void invalidateFrom(long offset) {
        generation++;
        if (offset < safe) safe = offset;
        if (offset < hashed) { digest = newDigest(); hashed = 0; }
        notifyAll();
    }
    synchronized long hashedBytes() { return hashed; }

    /**
     * Stops the background reader and hashes [hashed, length) after the final flush. Returns null when the incremental
     * digest cannot be trusted (a read failed or the prefix is past the end), so the caller hashes the whole file.
     */
    String finish(long length, CancellationScope cancellation) throws IOException, InterruptedException {
        // Closes the reader too: Windows cannot rename the partial file for publication while it is open.
        try (channel) {
            stop();
            long from;
            synchronized (this) { if (failed || hashed > length) return null; from = hashed; }
            var buffer = ByteBuffer.allocate(CHUNK);
            while (from < length) {
                cancellation.check();
                buffer.clear().limit((int) Math.min(CHUNK, length - from));
                read(buffer, from);
                buffer.flip(); digest.update(buffer); from += buffer.limit();
            }
            synchronized (this) { hashed = from; }
            return HexFormat.of().formatHex(digest.digest());
        }
    }

    @Override public void close() throws IOException {
        try { stop(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        finally { channel.close(); }
    }

    private void stop() throws InterruptedException {
        synchronized (this) { closed = true; notifyAll(); }
        worker.join();
    }

    private void run() {
        var buffer = ByteBuffer.allocate(CHUNK);
        try {
            for (;;) {
                long from, to, seen;
                synchronized (this) {
                    while (!closed && hashed >= safe) wait();
                    if (closed) return;
                    from = hashed; to = Math.min(safe, from + CHUNK); seen = generation;
                }
                buffer.clear().limit((int) (to - from));
                read(buffer, from);
                buffer.flip();
                synchronized (this) { if (seen == generation && hashed == from) { digest.update(buffer); hashed = to; } }
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            synchronized (this) { failed = true; }
        }
    }

    private void read(ByteBuffer buffer, long offset) throws IOException {
        long position = offset;
        while (buffer.hasRemaining()) {
            int count = channel.read(buffer, position);
            if (count < 0) throw new EOFException("Saved prefix is shorter than its checkpoint");
            position += count;
        }
    }

    private static MessageDigest newDigest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
