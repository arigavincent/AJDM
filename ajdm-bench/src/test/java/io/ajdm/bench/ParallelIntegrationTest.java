package io.ajdm.bench;

import io.ajdm.engine.AjdmEngine;
import io.ajdm.engine.EngineLimits;
import io.ajdm.model.*;
import io.ajdm.store.SqliteDownloadStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(45)
class ParallelIntegrationTest {
    @TempDir Path directory;
    private TransferRequest request(FaultyRangeServer server, String name) {
        var options = new TransferOptions(Duration.ofSeconds(3), Duration.ofSeconds(4), Duration.ofMillis(300),
                4096, 4, 65536, 2, Duration.ofMillis(1), Duration.ofMillis(10), server.sha256(), Duration.ofMillis(50))
                .withParallelism(4, 65536);
        return new TransferRequest(UUID.randomUUID(), server.uri(), directory.resolve(name), Map.of(), options);
    }
    private void awaitHighCoverage(AjdmEngine engine, io.ajdm.engine.TransferHandle handle) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (handle.progress().durableBytes() < 65536 && System.nanoTime() < until) {
            assertFalse(handle.completion().toCompletableFuture().isDone()); Thread.sleep(10);
        }
        var row = engine.downloads().getFirst();
        assertTrue(row.durableBytes() >= 65536); assertEquals(0, row.prefixLength());
        assertTrue(row.durableEnd() > row.durableBytes(), "High saved offsets must leave a gap below them");
    }
    @ParameterizedTest
    @EnumSource(value = FaultyRangeServer.Mode.class, names = {"NORMAL", "TRUNCATE_ONCE", "STALL_ONCE", "THROTTLE_ONCE",
            "IGNORE_RANGES_MIDWAY", "NO_RANGES", "NO_VALIDATOR", "UNKNOWN_LENGTH"})
    void parallelFaultsAndSingleStreamFallbackCompleteBitExactly(FaultyRangeServer.Mode mode) throws Exception {
        try (var server = new FaultyRangeServer(1024 * 1024 + 17, mode); var engine = new AjdmEngine(directory.resolve("state/ajdm.db"))) {
            var handle = engine.start(request(server, "result.bin"));
            var result = handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
            assertEquals(server.sha256(), result.sha256()); assertArrayEquals(server.data(), Files.readAllBytes(result.target()));
            assertEquals(result.bytes(), handle.progress().writtenBytes()); assertEquals(result.bytes(), handle.progress().durableBytes());
            assertEquals(DownloadState.COMPLETED, engine.downloads().getFirst().state());
        }
    }
    @ParameterizedTest
    @EnumSource(value = FaultyRangeServer.Mode.class, names = {"WRONG_RANGE", "WRONG_TOTAL", "ENCODED", "CHANGED_SOURCE", "CORRUPT_BODY", "TRUNCATE_ALWAYS"})
    void malformedChangedAndExhaustedParallelTransfersNeverPublish(FaultyRangeServer.Mode mode) throws Exception {
        try (var server = new FaultyRangeServer(1024 * 1024 + 17, mode); var engine = new AjdmEngine(directory.resolve("state/ajdm.db"))) {
            var request = request(server, "result.bin");
            var handle = engine.start(request);
            var failure = assertThrows(ExecutionException.class, () -> handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS));
            assertInstanceOf(DownloadException.class, failure.getCause());
            assertFalse(Files.exists(request.target())); assertEquals(mode == FaultyRangeServer.Mode.CHANGED_SOURCE ? DownloadState.PAUSED : DownloadState.FAILED, engine.downloads().getFirst().state());
            assertTrue(handle.progress().durableBytes() <= handle.progress().writtenBytes());
        }
    }
    @Test void fixedModeSplitsAStalledConnectionsWindowInsteadOfWaitingOnIt() throws Exception {
        int total = 4 * 1024 * 1024;
        try (var server = new FaultyRangeServer(total, FaultyRangeServer.Mode.BLOCK_FIRST_SEGMENT);
             var engine = new AjdmEngine(directory.resolve("state.db"))) {
            // One request window covers each connection's whole half, so only a mid-window cut can help.
            var options = new TransferOptions(Duration.ofSeconds(3), Duration.ofSeconds(4), Duration.ofSeconds(30),
                    4096, 8, total, 2, Duration.ofMillis(1), Duration.ofMillis(10), server.sha256(), Duration.ofMillis(50))
                    .withParallelism(2, 65536);
            var handle = engine.start(new TransferRequest(UUID.randomUUID(), server.uri(), directory.resolve("split.bin"), Map.of(), options));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (handle.progress().durableBytes() < total * 9L / 10 && System.nanoTime() < until) Thread.sleep(10);
            long whileStalled = handle.progress().durableBytes();
            server.releaseFirstSegment();
            var result = handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertTrue(whileStalled >= total * 9L / 10, "Only " + whileStalled + " bytes arrived while one connection stalled");
            assertEquals(server.sha256(), result.sha256());
            assertArrayEquals(server.data(), Files.readAllBytes(result.target()));
            assertEquals(total, new io.ajdm.model.RangeSet(server.requestedRanges()).coveredBytes());
            long discarded = handle.progress().receivedBytes() - result.bytes();
            assertTrue(discarded >= 0 && discarded <= total / 32, "Discarded " + discarded + " received bytes");
        }
    }
    @Test void extraConnectionsRejectedByAHostLimitShedTheirWorkInsteadOfFailingTheJob() throws Exception {
        int total = 4 * 1024 * 1024;
        try (var server = new FaultyRangeServer(total, FaultyRangeServer.Mode.CONNECTION_LIMITED, 512 * 1024);
             var engine = new AjdmEngine(directory.resolve("state.db"))) {
            // Only two retries per window: a rejected lane that kept retrying on its own would exhaust them and fail the job.
            var options = new TransferOptions(Duration.ofSeconds(3), Duration.ofSeconds(4), Duration.ofSeconds(10),
                    4096, 8, 256 * 1024, 2, Duration.ofMillis(1), Duration.ofMillis(10), server.sha256(), Duration.ofMillis(50))
                    .withParallelism(6, 65536);
            long started = System.nanoTime();
            var handle = engine.start(new TransferRequest(UUID.randomUUID(), server.uri(), directory.resolve("limited.bin"), Map.of(), options));
            var result = handle.completion().toCompletableFuture().get(30, TimeUnit.SECONDS);
            double seconds = (System.nanoTime() - started) / 1e9;
            assertEquals(server.sha256(), result.sha256());
            assertArrayEquals(server.data(), Files.readAllBytes(result.target()));
            assertTrue(server.rejections() > 0, "The host limit was never exercised");
            assertTrue(server.peakTransfers() <= 2);
            // Two admitted transfers at 512 KiB/s move 4 MiB in about 4 s; parked lanes must not leave the link idle.
            assertTrue(seconds < 9, "Took " + seconds + " s");
        }
    }
    @Test void pauseReopenRetainsDisjointSavedRangesAndDownloadsOnlyGaps() throws Exception {
        Path database = directory.resolve("state/ajdm.db");
        try (var server = new FaultyRangeServer(2 * 1024 * 1024 + 17, FaultyRangeServer.Mode.BLOCK_FIRST_SEGMENT)) {
            var request = request(server, "result.bin");
            DownloadRecord saved;
            try (var engine = new AjdmEngine(database)) {
                var handle = engine.start(request); awaitHighCoverage(engine, handle);
                handle.pause().toCompletableFuture().get(5, TimeUnit.SECONDS);
                saved = engine.downloads().getFirst(); assertEquals(0, saved.prefixLength());
                assertTrue(server.peakTransfers() > 1);
            }
            server.releaseFirstSegment();
            int before = server.requestedRanges().size();
            try (var engine = new AjdmEngine(database)) {
                var handle = engine.resume(request.id());
                var result = handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
                assertArrayEquals(server.data(), Files.readAllBytes(result.target()));
                assertEquals(result.bytes() - saved.durableBytes(), handle.progress().receivedBytes());
                assertNoSavedBytesRequested(saved.completed(), server.requestedRanges().subList(before, server.requestedRanges().size()));
            }
        }
    }
    @Test void unclaimedBytesInsideAFullLengthPartialAreNeverTrusted() throws Exception {
        Path database = directory.resolve("state/ajdm.db"), part = directory.resolve("manual.ajdmpart");
        try (var server = new FaultyRangeServer(1024 * 1024, FaultyRangeServer.Mode.NORMAL)) {
            var request = request(server, "result.bin");
            List<ByteRange> saved = List.of(new ByteRange(0, 65536), new ByteRange(524288, 786432));
            byte[] bytes = new byte[1024 * 1024]; java.util.Arrays.fill(bytes, (byte) 127);
            byte[] source = server.data();
            for (var range : saved) System.arraycopy(source, (int) range.start(), bytes, (int) range.start(), (int) range.length());
            Files.write(part, bytes);
            try (var store = new SqliteDownloadStore(database)) {
                store.create(request, part); store.state(request.id(), DownloadState.PROBING, null);
                store.describe(request.id(), new ProbeResult(server.uri(), bytes.length, true, "\"fixture-v1\"", null));
                store.state(request.id(), DownloadState.DOWNLOADING, null); store.checkpoint(request.id(), saved, DownloadState.PAUSED);
            }
            try (var engine = new AjdmEngine(database)) {
                var handle = engine.resume(request.id()); var result = handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
                assertArrayEquals(source, Files.readAllBytes(result.target()));
                assertEquals(bytes.length - new RangeSet(saved).coveredBytes(), handle.progress().receivedBytes());
                assertNoSavedBytesRequested(saved, server.requestedRanges());
            }
        }
    }
    @Test void truncationOfAHigherSavedRangeFailsEvenWhenItsPrefixIsIntact() throws Exception {
        Path database = directory.resolve("state/ajdm.db");
        try (var server = new FaultyRangeServer(2 * 1024 * 1024, FaultyRangeServer.Mode.BLOCK_FIRST_SEGMENT); var engine = new AjdmEngine(database)) {
            var request = request(server, "result.bin"); var handle = engine.start(request); awaitHighCoverage(engine, handle);
            handle.pause().toCompletableFuture().get();
            try (var channel = java.nio.channels.FileChannel.open(handle.partialPath().orElseThrow(), java.nio.file.StandardOpenOption.WRITE)) { channel.truncate(4096); }
            var failure = assertThrows(ExecutionException.class, () -> engine.resume(request.id()).completion().toCompletableFuture().get());
            assertEquals(ErrorCode.PART_FILE_TRUNCATED, ((DownloadException) failure.getCause()).code());
            assertEquals(0, engine.downloads().getFirst().durableBytes());
        }
    }
    @Test void globalAndHostCapsApplyAcrossDownloadsAndReleaseAfterPause() throws Exception {
        try (var server = new FaultyRangeServer(2 * 1024 * 1024, FaultyRangeServer.Mode.BLOCK_FIRST_SEGMENT);
             var engine = new AjdmEngine(directory.resolve("state/ajdm.db"), new EngineLimits(2, 2, 1024 * 1024))) {
            var first = engine.start(request(server, "first.bin")); var second = engine.start(request(server, "second.bin"));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (server.activeTransfers() < 2 && System.nanoTime() < until) Thread.sleep(10);
            assertEquals(2, server.activeTransfers()); assertTrue(server.peakTransfers() <= 2);
            first.pause().toCompletableFuture().get(5, TimeUnit.SECONDS); second.pause().toCompletableFuture().get(5, TimeUnit.SECONDS);
            server.releaseFirstSegment();
            assertEquals(server.sha256(), engine.resume(first.id()).completion().toCompletableFuture().get(15, TimeUnit.SECONDS).sha256());
            assertEquals(server.sha256(), engine.resume(second.id()).completion().toCompletableFuture().get(15, TimeUnit.SECONDS).sha256());
        }
    }
    @Test @Timeout(120) void seededNetworkFaultsAndUnevenBoundariesCompleteWithoutGaps() throws Exception {
        var random = new java.util.Random(49073);
        FaultyRangeServer.Mode[] modes = {FaultyRangeServer.Mode.NORMAL, FaultyRangeServer.Mode.TRUNCATE_ONCE,
                FaultyRangeServer.Mode.THROTTLE_ONCE, FaultyRangeServer.Mode.IGNORE_RANGES_MIDWAY, FaultyRangeServer.Mode.NO_RANGES};
        try (var engine = new AjdmEngine(directory.resolve("state/ajdm.db"))) {
            for (int trial = 0; trial < 20; trial++) {
                try (var server = new FaultyRangeServer(131072 + random.nextInt(524288), modes[random.nextInt(modes.length)])) {
                    var defaults = request(server, "random-" + trial + ".bin");
                    var old = defaults.options();
                    var options = new TransferOptions(old.connectTimeout(), old.requestTimeout(), old.stallTimeout(),
                            1024 * (1 + random.nextInt(16)), 1 + random.nextInt(8), 16384 + random.nextInt(114688),
                            old.maxRetries(), old.retryBaseDelay(), old.retryMaxDelay(), old.expectedSha256(), old.checkpointInterval(),
                            1 + random.nextInt(8), 16384);
                    var request = new TransferRequest(defaults.id(), defaults.uri(), defaults.target(), Map.of(), options);
                    var handle = engine.start(request);
                    var result = handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
                    assertArrayEquals(server.data(), Files.readAllBytes(result.target()));
                    assertEquals(result.bytes(), handle.progress().durableBytes());
                }
            }
        }
    }
    static void assertNoSavedBytesRequested(List<ByteRange> saved, List<ByteRange> requests) {
        for (var request : requests) for (var range : saved) {
            assertTrue(request.endExclusive() <= range.start() || request.start() >= range.endExclusive(), "Resume requested a saved byte");
        }
    }
}
