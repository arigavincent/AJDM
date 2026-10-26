package io.ajdm.engine;

import io.ajdm.net.CancellationScope;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PrefixHasherTest {
    private static final int MIB = 1024 * 1024;
    @TempDir Path temp;

    private static byte[] bytes(int length, int seed) { var data = new byte[length]; new Random(seed).nextBytes(data); return data; }
    private static String sha(byte[] data) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
    private static void awaitHashed(PrefixHasher hasher, long bytes) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (hasher.hashedBytes() < bytes && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(bytes, hasher.hashedBytes());
    }

    @Test void committedPrefixIsHashedDuringTheTransferAndFinishReadsOnlyTheTail() throws Exception {
        byte[] data = bytes(3 * MIB + 17, 1); Path file = Files.write(temp.resolve("part"), data);
        try (var hasher = new PrefixHasher(file)) {
            Thread.sleep(100);
            assertEquals(0, hasher.hashedBytes(), "Nothing is hashed before a checkpoint commits it");
            hasher.advance(2 * MIB); awaitHashed(hasher, 2 * MIB);
            assertEquals(sha(data), hasher.finish(data.length, new CancellationScope()));
            assertEquals(data.length, hasher.hashedBytes());
        }
    }

    @Test void invalidationBelowTheHashedPrefixRestartsTheDigestForRewrittenBytes() throws Exception {
        byte[] first = bytes(2 * MIB, 2); Path file = Files.write(temp.resolve("part"), first);
        try (var hasher = new PrefixHasher(file)) {
            hasher.advance(first.length); awaitHashed(hasher, first.length);
            hasher.invalidateFrom(MIB);
            assertEquals(0, hasher.hashedBytes());
            byte[] rewritten = first.clone(); System.arraycopy(bytes(MIB, 3), 0, rewritten, MIB, MIB);
            try (var channel = FileChannel.open(file, StandardOpenOption.WRITE)) { channel.write(ByteBuffer.wrap(rewritten, MIB, MIB), MIB); }
            hasher.advance(rewritten.length);
            String digest = hasher.finish(rewritten.length, new CancellationScope());
            assertNotEquals(sha(first), digest); assertEquals(sha(rewritten), digest);
        }
    }

    @Test void racingInvalidationsAndRewritesNeverProduceAStaleDigest() throws Exception {
        var random = new Random(4);
        for (int round = 0; round < 40; round++) {
            byte[] data = bytes(4 * MIB, round); Path file = Files.write(temp.resolve("race-" + round), data);
            try (var hasher = new PrefixHasher(file); var channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                hasher.advance(data.length);
                for (int change = 0; change < 3; change++) {
                    Thread.sleep(random.nextInt(4));
                    int offset = random.nextInt(data.length - 4096);
                    // The engine's order: invalidate the claim, rewrite the bytes, then commit them again.
                    hasher.invalidateFrom(offset);
                    byte[] patch = bytes(4096, round * 10 + change); System.arraycopy(patch, 0, data, offset, patch.length);
                    channel.write(ByteBuffer.wrap(patch), offset);
                    hasher.advance(data.length);
                }
                String digest = hasher.finish(data.length, new CancellationScope());
                assertTrue(digest == null || digest.equals(sha(data)), "Round " + round + " produced a digest of stale bytes");
            }
        }
    }

    @Test void aSavedPrefixShorterThanItsCheckpointIsNotTrusted() throws Exception {
        Path file = Files.write(temp.resolve("part"), bytes(MIB, 5));
        try (var hasher = new PrefixHasher(file)) {
            hasher.advance(2L * MIB);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (hasher.hashedBytes() < MIB && System.nanoTime() < deadline) Thread.sleep(5);
            Thread.sleep(100);
            assertNull(hasher.finish(2L * MIB, new CancellationScope()), "A failed read must send the caller to a full hash");
        }
    }

    @Test void closingReleasesTheFileForReplacement() throws Exception {
        Path file = Files.write(temp.resolve("part"), bytes(MIB, 6));
        var hasher = new PrefixHasher(file); hasher.advance(MIB);
        hasher.finish(MIB, new CancellationScope());
        Files.move(file, temp.resolve("published"), StandardCopyOption.ATOMIC_MOVE);
        hasher.close();
    }
}
