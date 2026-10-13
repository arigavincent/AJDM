package io.ajdm.engine;

import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import io.ajdm.model.DownloadException;
import io.ajdm.model.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BoundedFileWriterTest {
    @TempDir Path directory;
    @Test void preallocationAndRewindsNeverClaimUnwrittenBytes() throws Exception {
        try (var channel = FileChannel.open(directory.resolve("allocated"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
             var disk = Executors.newSingleThreadExecutor()) {
            var written = new AtomicLong();
            var writer = new BoundedFileWriter(channel, disk, 4096, 2, written);
            writer.preallocate(320017);
            assertEquals(320017, channel.size()); assertEquals(0, written.get()); assertTrue(writer.coverage().isEmpty());
            byte[] buffer = writer.acquire(); writer.submit(buffer, 4096, 0); writer.drain();
            writer.rewind(100);
            assertEquals(320017, channel.size()); assertEquals(100, written.get());
            assertEquals(java.util.List.of(new io.ajdm.model.ByteRange(0, 100)), writer.coverage());
            writer.reset();
            assertEquals(320017, channel.size()); assertEquals(0, written.get()); assertTrue(writer.coverage().isEmpty());
            writer.preallocate(0); assertEquals(0, channel.size());
            writer.preallocate(-1); assertEquals(0, channel.size());
        }
    }

    @Test void allocationFailureDoesNotAcknowledgeCoverage() throws Exception {
        FileChannel channel = FileChannel.open(directory.resolve("allocation-failure"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        channel.close();
        try (var disk = Executors.newSingleThreadExecutor()) {
            var written = new AtomicLong();
            var writer = new BoundedFileWriter(channel, disk, 4096, 1, written);
            assertThrows(DownloadException.class, () -> writer.preallocate(320017));
            assertTrue(writer.coverage().isEmpty()); assertEquals(0, written.get());
        }
    }
    @Test void outOfOrderWritesCountOnlyDistinctAcknowledgedBytes() throws Exception {
        try (var channel = FileChannel.open(directory.resolve("disjoint"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
             var disk = Executors.newSingleThreadExecutor()) {
            var written = new AtomicLong();
            var writer = new BoundedFileWriter(channel, disk, 4096, 2, written);
            byte[] high = writer.acquire(); writer.submit(high, 4096, 8192); writer.drain();
            assertEquals(4096, written.get(), "A high offset must not claim the unwritten gap below it");
            byte[] low = writer.acquire(); writer.submit(low, 4096, 0); writer.drain();
            assertEquals(8192, written.get());
            byte[] retry = writer.acquire(); writer.submit(retry, 4096, 8192); writer.drain();
            assertEquals(8192, written.get(), "Retrying an owned range must not double count coverage");
            assertEquals(java.util.List.of(new io.ajdm.model.ByteRange(0, 4096), new io.ajdm.model.ByteRange(8192, 12288)),
                    writer.flush(java.util.UUID.randomUUID(), CheckpointObserver.none()).get(1, TimeUnit.SECONDS));
        }
    }
    @Test void flushBarrierExcludesWritesSubmittedAfterItsSnapshot() throws Exception {
        Path file = directory.resolve("checkpoint-file");
        try (var channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
             var disk = Executors.newSingleThreadExecutor()) {
            var written = new AtomicLong();
            var writer = new BoundedFileWriter(channel, disk, 4096, 2, written);
            byte[] first = writer.acquire(); first[0] = 11; writer.submit(first, 1, 0);
            CountDownLatch forced = new CountDownLatch(1), commit = new CountDownLatch(1);
            var checkpoint = writer.flush(java.util.UUID.randomUUID(), (phase, id, bytes) -> {
                if (phase == CheckpointObserver.Phase.AFTER_FILE_FORCE) {
                    forced.countDown();
                    try { commit.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException(e); }
                }
            });
            try {
                assertTrue(forced.await(1, TimeUnit.SECONDS));
                byte[] second = writer.acquire(); second[0] = 22; writer.submit(second, 1, 1);
                assertEquals(1, written.get());
                commit.countDown();
                assertEquals(java.util.List.of(new io.ajdm.model.ByteRange(0, 1)), checkpoint.get(1, TimeUnit.SECONDS));
                writer.drain(); assertEquals(2, written.get());
                assertArrayEquals(new byte[]{11, 22}, Files.readAllBytes(file));
            } finally { commit.countDown(); }
        }
    }
    @Test void partialWritesAdvanceOffsetsUntilAllBytesAreWritten() throws Exception {
        byte[] source = new byte[1003], target = new byte[1020];
        new java.util.Random(41).nextBytes(source);
        AtomicInteger calls = new AtomicInteger();
        BoundedFileWriter.writeFully((buffer, offset) -> {
            if (calls.getAndIncrement() < 2) return 0;
            int length = Math.min(7, buffer.remaining());
            buffer.get(target, (int) offset, length);
            return length;
        }, ByteBuffer.wrap(source), 17);
        assertArrayEquals(source, java.util.Arrays.copyOfRange(target, 17, target.length));
        assertTrue(calls.get() > 100);
    }

    @Test void diskFailureNeverAcknowledgesBytesAndReturnsOwnedBuffers() throws Exception {
        Path file = directory.resolve("closed-file");
        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        channel.close();
        try (var disk = Executors.newSingleThreadExecutor()) {
            AtomicLong written = new AtomicLong();
            var writer = new BoundedFileWriter(channel, disk, 4096, 1, written);
            byte[] buffer = writer.acquire();
            writer.submit(buffer, 1, 0);
            var failure = assertThrows(DownloadException.class, writer::drain);
            assertEquals(ErrorCode.DISK_WRITE_ERROR, failure.code());
            assertEquals(0, written.get());
            assertThrows(DownloadException.class, writer::acquire);
        }
    }
    @Test void queuedBytesAreNotAcknowledgedAndBuffersAreNotReusedBeforeCompletion() throws Exception {
        Path file = directory.resolve("partial");
        try (var channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
             var disk = Executors.newSingleThreadExecutor()) {
            CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
            disk.submit(() -> { blocked.countDown(); try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
            assertTrue(blocked.await(1, TimeUnit.SECONDS));
            AtomicLong written = new AtomicLong();
            var writer = new BoundedFileWriter(channel, disk, 4096, 1, written);
            try {
                byte[] first = writer.acquire(); first[0] = 42;
                writer.submit(first, 1, 0);
                assertEquals(0, written.get(), "A queued write is not completed coverage");
                CountDownLatch acquired = new CountDownLatch(1), waiting = new CountDownLatch(1);
                Thread waiter = Thread.ofVirtual().start(() -> {
                    waiting.countDown();
                    try { byte[] next = writer.acquire(); acquired.countDown(); writer.release(next); }
                    catch (Exception e) { throw new AssertionError(e); }
                });
                assertTrue(waiting.await(1, TimeUnit.SECONDS));
                assertFalse(acquired.await(100, TimeUnit.MILLISECONDS), "In-flight buffer must not be available");
                release.countDown();
                assertTrue(acquired.await(1, TimeUnit.SECONDS));
                waiter.join(); writer.drain();
                assertEquals(1, written.get());
                assertArrayEquals(new byte[]{42}, Files.readAllBytes(file));
            } finally { release.countDown(); }
        }
    }
    @Test void queueMetricCountsSubmittedJobsAndSettlesToZeroAfterSuccessOrFailure() throws Exception {
        Path file = directory.resolve("queue-metric");
        try (var channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
             var disk = Executors.newSingleThreadExecutor()) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            disk.submit(() -> { entered.countDown(); try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            var writer = new BoundedFileWriter(channel, disk, 4096, 2, new AtomicLong());
            try {
                var first = writer.acquire(); writer.submit(first, 123, 0);
                var second = writer.acquire(); writer.submit(second, 321, 123);
                assertEquals(444, writer.queuedBytes());
            } finally { release.countDown(); }
            writer.drain(); assertEquals(0, writer.queuedBytes());
            FileChannel failedChannel = FileChannel.open(directory.resolve("queue-failure"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            failedChannel.close();
            var failedWriter = new BoundedFileWriter(failedChannel, disk, 4096, 1, new AtomicLong());
            var buffer = failedWriter.acquire(); failedWriter.submit(buffer, 77, 0);
            assertThrows(DownloadException.class, failedWriter::drain); assertEquals(0, failedWriter.queuedBytes());
        }
    }

}
