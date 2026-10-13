package io.ajdm.bench;

import io.ajdm.engine.AjdmEngine;
import io.ajdm.model.DownloadException;
import io.ajdm.model.DownloadState;
import io.ajdm.model.ErrorCode;
import io.ajdm.model.TransferOptions;
import io.ajdm.model.TransferRequest;
import java.nio.file.Files;
import java.nio.file.Path;
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
import static io.ajdm.bench.FaultyRangeServer.Mode;

@Timeout(15)
class EngineIntegrationTest {
    @TempDir Path directory;
    private TransferRequest request(FaultyRangeServer server, String hash) {
        return new TransferRequest(UUID.randomUUID(), server.uri(), directory.resolve("download.bin"), Map.of(),
                new TransferOptions(Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofMillis(250),
                        4096, 2, 65536, 2, Duration.ofMillis(1), Duration.ofMillis(10), hash));
    }

    @ParameterizedTest
    @EnumSource(value = Mode.class, names = {"NORMAL", "HEAD_REJECTED", "NO_RANGES", "UNKNOWN_LENGTH",
            "TRUNCATE_ONCE", "STALL_ONCE", "THROTTLE_ONCE", "IGNORE_RANGES_MIDWAY", "NO_VALIDATOR", "NO_RANGES_TRUNCATE_ONCE"})
    void completesBitIdenticallyAcrossSupportedFaults(Mode mode) throws Exception {
        try (var server = new FaultyRangeServer(320_017, mode); var engine = new AjdmEngine(directory.resolve("state/ajdm.db"))) {
            var handle = engine.start(request(server, server.sha256()));
            var result = handle.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertArrayEquals(server.data(), Files.readAllBytes(result.target()));
            assertEquals(server.sha256(), result.sha256());
            assertEquals(320_017, result.bytes());
            assertEquals(DownloadState.COMPLETED, handle.progress().state());
            assertEquals(result.bytes(), handle.progress().writtenBytes());
            assertEquals(result.bytes(), handle.progress().durableBytes());
            try (var files = Files.list(directory)) { assertEquals(1, files.filter(Files::isRegularFile).count()); }
        }
    }

    @Test void emptyFileCompletes() throws Exception {
        try (var server = new FaultyRangeServer(0, Mode.NORMAL); var engine = new AjdmEngine(directory.resolve("state/ajdm.db"))) {
            var result = engine.start(request(server, server.sha256())).completion().toCompletableFuture().get();
            assertEquals(0, Files.size(result.target()));
            assertEquals(server.sha256(), result.sha256());
        }
    }

    @ParameterizedTest
    @EnumSource(value = Mode.class, names = {"WRONG_RANGE", "WRONG_TOTAL", "CHANGED_SOURCE", "ENCODED",
            "CORRUPT_BODY", "TRUNCATE_ALWAYS", "STALL_ALWAYS", "THROTTLE_ALWAYS", "EXCESS_LENGTH"})
    void neverPublishesInvalidOrUnfinishedData(Mode mode) throws Exception {
        try (var server = new FaultyRangeServer(200_003, mode); var engine = new AjdmEngine(directory.resolve("state/ajdm.db"))) {
            var handle = engine.start(request(server, server.sha256()));
            var failure = assertThrows(ExecutionException.class,
                    () -> handle.completion().toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertInstanceOf(DownloadException.class, failure.getCause());
            assertEquals(mode == Mode.CHANGED_SOURCE ? DownloadState.PAUSED : DownloadState.FAILED, handle.progress().state());
            assertFalse(Files.exists(directory.resolve("download.bin")));
            assertTrue(handle.progress().durableBytes() <= handle.progress().writtenBytes());
            assertEquals(handle.progress().durableBytes(), engine.downloads().getFirst().durableBytes());
            assertTrue(server.transferRequests() <= 6, "Retries must terminate");
        }
    }

    @Test void checksumMismatchHasSpecificFailure() throws Exception {
        try (var server = new FaultyRangeServer(12345, Mode.NORMAL); var engine = new AjdmEngine(directory.resolve("state/ajdm.db"))) {
            var failure = assertThrows(ExecutionException.class, () -> engine.start(request(server, "0".repeat(64)))
                    .completion().toCompletableFuture().get());
            assertEquals(ErrorCode.CHECKSUM_MISMATCH, ((DownloadException) failure.getCause()).code());
        }
    }

    @ParameterizedTest
    @EnumSource(value = Mode.class, names = {"STALL_ALWAYS", "HEADER_STALL"})
    void cancellationStopsBlockedNetworkAndKeepsDurableProgressHonest(Mode mode) throws Exception {
        try (var server = new FaultyRangeServer(200_000, mode); var engine = new AjdmEngine(directory.resolve("state/ajdm.db"))) {
            var handle = engine.start(request(server, null));
            assertTrue(server.awaitTransfer());
            handle.cancel();
            var failure = assertThrows(ExecutionException.class,
                    () -> handle.completion().toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertEquals(ErrorCode.CANCELED, ((DownloadException) failure.getCause()).code());
            assertEquals(DownloadState.CANCELED, handle.progress().state());
            assertEquals(0, handle.progress().durableBytes());
            assertFalse(Files.exists(directory.resolve("download.bin")));
        }
    }

    @Test void destinationCreatedWhileDownloadingIsNeverOverwritten() throws Exception {
        try (var server = new FaultyRangeServer(200_000, Mode.STALL_ONCE); var engine = new AjdmEngine(directory.resolve("state/ajdm.db"))) {
            var handle = engine.start(request(server, server.sha256()));
            assertTrue(server.awaitTransfer());
            Files.writeString(directory.resolve("download.bin"), "created during transfer");
            var failure = assertThrows(ExecutionException.class,
                    () -> handle.completion().toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals(ErrorCode.TARGET_EXISTS, ((DownloadException) failure.getCause()).code());
            assertEquals("created during transfer", Files.readString(directory.resolve("download.bin")));
        }
    }

    @Test void existingDestinationIsNeverOverwritten() throws Exception {
        Path target = directory.resolve("download.bin");
        Files.writeString(target, "keep my file");
        try (var server = new FaultyRangeServer(12345, Mode.NORMAL); var engine = new AjdmEngine(directory.resolve("state/ajdm.db"))) {
            var failure = assertThrows(ExecutionException.class,
                    () -> engine.start(request(server, null)).completion().toCompletableFuture().get());
            assertEquals(ErrorCode.TARGET_EXISTS, ((DownloadException) failure.getCause()).code());
            assertEquals("keep my file", Files.readString(target));
        }
    }

    @Test void closingEnginePausesActiveTransfers() throws Exception {
        try (var server = new FaultyRangeServer(200_000, Mode.STALL_ALWAYS)) {
            var engine = new AjdmEngine(directory.resolve("state/ajdm.db"));
            var handle = engine.start(request(server, null));
            try { assertTrue(server.awaitTransfer()); } finally { engine.close(); }
            assertTrue(handle.completion().toCompletableFuture().isDone());
            assertEquals(DownloadState.PAUSED, handle.progress().state());
            assertThrows(IllegalStateException.class, () -> engine.start(request(server, null)));
        }
    }
}
