package io.ajdm.bench;

import io.ajdm.engine.AjdmEngine;
import io.ajdm.engine.TransferHandle;
import io.ajdm.engine.CheckpointObserver;
import io.ajdm.model.*;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
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

@Timeout(20)
class ResumeIntegrationTest {
    @TempDir Path directory;
    private Path database() { return directory.resolve("state/ajdm.db"); }
    private TransferRequest request(FaultyRangeServer server) {
        return new TransferRequest(UUID.randomUUID(), server.uri(), directory.resolve("download.bin"), Map.of(),
                new TransferOptions(Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(2), 4096, 2,
                        262144, 2, Duration.ofMillis(1), Duration.ofMillis(10), server.sha256(), Duration.ofMillis(50)));
    }
    private void awaitCheckpoint(TransferHandle handle) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (handle.progress().durableBytes() < 65536 && System.nanoTime() < end) {
            if (handle.completion().toCompletableFuture().isDone()) fail("Transfer stopped before checkpoint");
            Thread.sleep(10);
        }
        assertTrue(handle.progress().durableBytes() >= 65536);
    }
    @ParameterizedTest
    @EnumSource(value = FaultyRangeServer.Mode.class, names = {"SLOW", "SLOW_LAST_MODIFIED"})
    void pauseReopenAndResumeSkipsTheDurablePrefix(FaultyRangeServer.Mode mode) throws Exception {
        try (var server = new FaultyRangeServer(2 * 1024 * 1024 + 17, mode)) {
            var request = request(server);
            long durable;
            try (var engine = new AjdmEngine(database())) {
                var handle = engine.start(request); awaitCheckpoint(handle);
                handle.pause().toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(DownloadState.PAUSED, handle.progress().state());
                durable = handle.progress().durableBytes();
                assertTrue(durable < 2 * 1024 * 1024);
                assertEquals(durable, engine.downloads().getFirst().durableBytes());
                assertEquals(ErrorCode.PAUSED, ((DownloadException) assertThrows(ExecutionException.class,
                        () -> handle.completion().toCompletableFuture().get()).getCause()).code());
            }
            int previousRequests = server.requestedRanges().size();
            try (var engine = new AjdmEngine(database())) {
                var resumed = engine.resume(request.id());
                var result = resumed.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
                assertArrayEquals(server.data(), Files.readAllBytes(result.target()));
                assertEquals(durable, server.requestedRanges().get(previousRequests).start());
                assertEquals(result.bytes() - durable, resumed.progress().receivedBytes());
                assertEquals(DownloadState.COMPLETED, engine.downloads().getFirst().state());
            }
        }
    }
    @Test void gracefulShutdownPersistsPausedProgress() throws Exception {
        try (var server = new FaultyRangeServer(2 * 1024 * 1024, FaultyRangeServer.Mode.SLOW)) {
            var request = request(server);
            var engine = new AjdmEngine(database());
            var handle = engine.start(request);
            try { awaitCheckpoint(handle); } finally { engine.close(); }
            assertEquals(DownloadState.PAUSED, handle.progress().state());
            try (var reopened = new AjdmEngine(database())) {
                assertEquals(handle.progress().durableBytes(), reopened.downloads().getFirst().durableBytes());
                assertArrayEquals(server.data(), Files.readAllBytes(reopened.resume(request.id()).completion().toCompletableFuture().get().target()));
            }
        }
    }
    @Test void changedValidatorDoesNotSpliceOrDestroyOldPartial() throws Exception {
        try (var server = new FaultyRangeServer(2 * 1024 * 1024, FaultyRangeServer.Mode.SLOW); var engine = new AjdmEngine(database())) {
            var request = request(server); var handle = engine.start(request); awaitCheckpoint(handle);
            handle.pause().toCompletableFuture().get();
            Path partial = handle.partialPath().orElseThrow(); byte[] old = Files.readAllBytes(partial);
            server.etag("\"fixture-v2\"");
            var failure = assertThrows(ExecutionException.class, () -> engine.resume(request.id()).completion().toCompletableFuture().get());
            assertEquals(ErrorCode.SOURCE_CHANGED, ((DownloadException) failure.getCause()).code());
            assertArrayEquals(old, Files.readAllBytes(partial)); assertFalse(Files.exists(request.target()));
        }
    }
    @Test void missingPartialClearsClaimsAndFailsExplicitly() throws Exception {
        try (var server = new FaultyRangeServer(2 * 1024 * 1024, FaultyRangeServer.Mode.SLOW); var engine = new AjdmEngine(database())) {
            var request = request(server); var handle = engine.start(request); awaitCheckpoint(handle);
            handle.pause().toCompletableFuture().get(); Files.delete(handle.partialPath().orElseThrow());
            var failure = assertThrows(ExecutionException.class, () -> engine.resume(request.id()).completion().toCompletableFuture().get());
            assertEquals(ErrorCode.PART_FILE_MISSING, ((DownloadException) failure.getCause()).code());
            assertEquals(0, engine.downloads().getFirst().durableBytes());
        }
    }
    @Test void truncatedPartialIsNotPaddedAndTrusted() throws Exception {
        try (var server = new FaultyRangeServer(2 * 1024 * 1024, FaultyRangeServer.Mode.SLOW); var engine = new AjdmEngine(database())) {
            var request = request(server); var handle = engine.start(request); awaitCheckpoint(handle);
            handle.pause().toCompletableFuture().get();
            try (var channel = FileChannel.open(handle.partialPath().orElseThrow(), StandardOpenOption.WRITE)) { channel.truncate(1); }
            var failure = assertThrows(ExecutionException.class, () -> engine.resume(request.id()).completion().toCompletableFuture().get());
            assertEquals(ErrorCode.PART_FILE_TRUNCATED, ((DownloadException) failure.getCause()).code());
            assertEquals(0, engine.downloads().getFirst().durableBytes()); assertFalse(Files.exists(request.target()));
        }
    }
    @ParameterizedTest
    @EnumSource(value = FaultyRangeServer.Mode.class, names = {"SLOW_NO_RANGES", "SLOW_NO_VALIDATOR", "SLOW_UNKNOWN_LENGTH"})
    void unsafeResumeRestartsFromZero(FaultyRangeServer.Mode mode) throws Exception {
        try (var server = new FaultyRangeServer(1024 * 1024, mode); var engine = new AjdmEngine(database())) {
            var request = request(server); var handle = engine.start(request); awaitCheckpoint(handle);
            handle.pause().toCompletableFuture().get();
            assertFalse(engine.downloads().getFirst().resumeSupported());
            var resumed = engine.resume(request.id());
            var result = resumed.completion().toCompletableFuture().get();
            assertArrayEquals(server.data(), Files.readAllBytes(result.target()));
            assertEquals(result.bytes(), resumed.progress().receivedBytes());
        }
    }
    @Test void forceFailureNeverCommitsAFalseCheckpoint() throws Exception {
        CheckpointObserver brokenDisk = (phase, id, bytes) -> {
            if (phase == CheckpointObserver.Phase.BEFORE_FILE_FORCE && bytes > 0) throw new java.io.IOException("Injected force failure");
        };
        try (var server = new FaultyRangeServer(1024 * 1024, FaultyRangeServer.Mode.SLOW);
             var engine = new AjdmEngine(database(), brokenDisk)) {
            var handle = engine.start(request(server));
            var failure = assertThrows(ExecutionException.class, () -> handle.completion().toCompletableFuture().get());
            assertEquals(ErrorCode.DISK_WRITE_ERROR, ((DownloadException) failure.getCause()).code());
            assertEquals(0, engine.downloads().getFirst().durableBytes());
            assertEquals(0, handle.progress().durableBytes());
            assertFalse(Files.exists(directory.resolve("download.bin")));
        }
    }
}
