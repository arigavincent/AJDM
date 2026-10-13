package io.ajdm.bench;

import io.ajdm.engine.*;
import io.ajdm.model.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import io.ajdm.store.SqliteDownloadStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class DiskAdmissionTest {
    @TempDir Path directory;
    private static final long RESERVE = 64L * 1024 * 1024;

    private TransferRequest request(FaultyRangeServer server) {
        return new TransferRequest(UUID.randomUUID(), server.uri(), directory.resolve("result.bin"), Map.of(),
                new TransferOptions(Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(2),
                        4096, 2, 65536, 2, Duration.ofMillis(1), Duration.ofMillis(10), server.sha256(), Duration.ofMillis(50)));
    }

    private AjdmEngine engine(DiskSpaceProbe probe) throws Exception {
        return new AjdmEngine(directory.resolve("state/ajdm.db"), EngineLimits.defaults(), CheckpointObserver.none(), probe);
    }

    @Test void rejectsBeforePayloadAndPersistsSpecificSpaceError() throws Exception {
        try (var server = new FaultyRangeServer(320017, FaultyRangeServer.Mode.NORMAL);
             var engine = engine(path -> RESERVE + 320016)) {
            var handle = engine.start(request(server));
            var error = assertThrows(ExecutionException.class, () -> handle.completion().toCompletableFuture().get()).getCause();
            assertEquals(ErrorCode.INSUFFICIENT_DISK_SPACE, ((DownloadException) error).code());
            assertEquals(0, server.transferRequests());
            assertEquals(0, handle.progress().durableBytes());
            assertEquals(0, Files.size(handle.partialPath().orElseThrow()));
            assertEquals(ErrorCode.INSUFFICIENT_DISK_SPACE, engine.downloads().getFirst().error());
            assertFalse(Files.exists(directory.resolve("result.bin")));
        }
    }

    @Test void exactCapacityAdmitsAndChecksTheDestinationFilesystem() throws Exception {
        try (var server = new FaultyRangeServer(320017, FaultyRangeServer.Mode.NORMAL);
             var engine = engine(path -> {
                 assertEquals(directory, path);
                 return RESERVE + 320017;
             })) {
            var result = engine.start(request(server)).completion().toCompletableFuture().get();
            assertArrayEquals(server.data(), Files.readAllBytes(result.target()));
        }
    }

    @Test void unknownLengthRequiresReserveBeforeAnyFullBody() throws Exception {
        try (var server = new FaultyRangeServer(320017, FaultyRangeServer.Mode.UNKNOWN_LENGTH);
             var engine = engine(path -> RESERVE - 1)) {
            var error = assertThrows(ExecutionException.class,
                    () -> engine.start(request(server)).completion().toCompletableFuture().get()).getCause();
            assertEquals(ErrorCode.INSUFFICIENT_DISK_SPACE, ((DownloadException) error).code());
            assertEquals(0, server.transferRequests());
        }
    }

    @Test void capacityReadFailureStopsPayloadWithoutLeakingItsMessage() throws Exception {
        try (var server = new FaultyRangeServer(320017, FaultyRangeServer.Mode.NORMAL);
             var engine = engine(path -> { throw new java.io.IOException("secret filesystem details"); })) {
            var error = (DownloadException) assertThrows(ExecutionException.class,
                    () -> engine.start(request(server)).completion().toCompletableFuture().get()).getCause();
            assertEquals(ErrorCode.DISK_WRITE_ERROR, error.code());
            assertFalse(error.userMessage().contains("secret"));
            assertEquals(0, server.transferRequests());
        }
    }

    @Test void refusedResumePreservesSavedCoverageAndPartialThenCanRetry() throws Exception {
        AtomicLong usable = new AtomicLong(Long.MAX_VALUE);
        try (var server = new FaultyRangeServer(1024 * 1024, FaultyRangeServer.Mode.SLOW)) {
            var request = request(server);
            long durable;
            Path partial;
            byte[] saved;
            try (var engine = engine(path -> usable.get())) {
                var handle = engine.start(request);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (handle.progress().durableBytes() < 65536 && System.nanoTime() < deadline) Thread.sleep(10);
                assertTrue(handle.progress().durableBytes() >= 65536);
                handle.pause().toCompletableFuture().get();
                durable = handle.progress().durableBytes();
                partial = handle.partialPath().orElseThrow(); saved = Files.readAllBytes(partial);
            }
            int requests = server.transferRequests();
            usable.set(RESERVE + 1024 * 1024 - 1);
            try (var engine = engine(path -> usable.get())) {
                var before = engine.downloads().getFirst().completed();
                var failure = assertThrows(ExecutionException.class,
                        () -> engine.resume(request.id()).completion().toCompletableFuture().get()).getCause();
                assertEquals(ErrorCode.INSUFFICIENT_DISK_SPACE, ((DownloadException) failure).code());
                assertEquals(requests, server.transferRequests());
                assertEquals(before, engine.downloads().getFirst().completed());
                assertEquals(durable, engine.downloads().getFirst().durableBytes());
                assertArrayEquals(saved, Files.readAllBytes(partial));
                usable.set(Long.MAX_VALUE);
                var resumed = engine.resume(request.id());
                var result = resumed.completion().toCompletableFuture().get();
                assertArrayEquals(server.data(), Files.readAllBytes(result.target()));
                assertEquals(result.bytes() - durable, resumed.progress().receivedBytes());
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void refreshRejectedForSpaceDoesNotCommitSourceOrRequestReplacementPayload(boolean adaptive) throws Exception {
        var usable = new AtomicLong(Long.MAX_VALUE);
        var coverage = new AtomicReference<List<ByteRange>>();
        var bytes = new AtomicReference<byte[]>();
        try (var server = new ExpiringSourceServer(); var engine = engine(path -> usable.get())) {
            var options = new TransferOptions(Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(2),
                    4096, 4, 65536, 2, Duration.ofMillis(1), Duration.ofMillis(10), server.sha256(),
                    Duration.ofMillis(50), 4, 65536, adaptive);
            var request = new TransferRequest(UUID.randomUUID(), server.uri(false), directory.resolve("result.bin"), Map.of(), options);
            var handle = engine.start(request, context -> {
                var saved = engine.downloads().getFirst();
                coverage.set(saved.completed()); bytes.set(Files.readAllBytes(saved.partialPath()));
                usable.set(RESERVE + server.data.length - 1);
                return server.replacement();
            });
            var failure = (DownloadException) assertThrows(ExecutionException.class,
                    () -> handle.completion().toCompletableFuture().get()).getCause();
            assertEquals(ErrorCode.INSUFFICIENT_DISK_SPACE, failure.code());
            assertFalse(coverage.get().isEmpty());
            var saved = engine.downloads().getFirst();
            assertEquals(request.uri(), saved.request().uri());
            assertEquals(coverage.get(), saved.completed());
            assertArrayEquals(bytes.get(), Files.readAllBytes(saved.partialPath()));
            assertEquals(0, server.replacementPayloads.get());
            assertFalse(Files.exists(request.target()));
        }
    }

    @Test void verifiedPublicationRecoveryRequiresNoPayloadSpaceOrProbe() throws Exception {
        try (var server = new FaultyRangeServer(320017, FaultyRangeServer.Mode.NORMAL)) {
            var request = request(server);
            Path part = directory.resolve("verified.ajdmpart");
            Files.write(part, server.data());
            try (var channel = java.nio.channels.FileChannel.open(part, java.nio.file.StandardOpenOption.WRITE)) { channel.force(true); }
            try (var store = new SqliteDownloadStore(directory.resolve("state/ajdm.db"))) {
                store.create(request, part); store.state(request.id(), DownloadState.PROBING, null);
                store.describe(request.id(), new ProbeResult(request.uri(), server.data().length, true, "\"fixture-v1\"", null));
                store.state(request.id(), DownloadState.DOWNLOADING, null);
                store.checkpoint(request.id(), List.of(new ByteRange(0, server.data().length)), DownloadState.VERIFYING);
                store.ready(request.id(), server.sha256());
            }
            try (var engine = engine(path -> { throw new java.io.IOException("No payload capacity lookup should occur"); })) {
                var result = engine.resume(request.id()).completion().toCompletableFuture().get();
                assertEquals(server.sha256(), result.sha256());
                assertArrayEquals(server.data(), Files.readAllBytes(result.target()));
                assertEquals(0, server.requests());
            }
        }
    }
}
