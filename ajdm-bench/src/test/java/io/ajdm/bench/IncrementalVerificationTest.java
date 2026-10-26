package io.ajdm.bench;

import io.ajdm.engine.AjdmEngine;
import io.ajdm.engine.TransferHandle;
import io.ajdm.model.*;
import java.nio.ByteBuffer;
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

/** End-to-end checks of SHA-256 verification computed during the transfer (PrefixHasher). */
@Timeout(30)
class IncrementalVerificationTest {
    @TempDir Path directory;
    private Path database() { return directory.resolve("state/ajdm.db"); }
    private TransferRequest request(FaultyRangeServer server, int connections) {
        return new TransferRequest(UUID.randomUUID(), server.uri(), directory.resolve("download.bin"), Map.of(),
                new TransferOptions(Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(2), 4096, 2,
                        262144, 2, Duration.ofMillis(1), Duration.ofMillis(10), server.sha256(), Duration.ofMillis(50))
                        .withParallelism(connections, 65536));
    }
    private static long pauseAfterCheckpoint(TransferHandle handle) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (handle.progress().durableBytes() < 256 * 1024 && System.nanoTime() < end) Thread.sleep(10);
        handle.pause().toCompletableFuture().get(5, TimeUnit.SECONDS);
        long durable = handle.progress().durableBytes(); assertTrue(durable >= 256 * 1024, "No saved prefix before the pause");
        return durable;
    }

    @Test void aResumedDownloadHashesTheRestoredPrefixAndVerifies() throws Exception {
        try (var server = new FaultyRangeServer(2 * 1024 * 1024 + 17, FaultyRangeServer.Mode.SLOW)) {
            var request = request(server, 1);
            try (var engine = new AjdmEngine(database())) { pauseAfterCheckpoint(engine.start(request)); }
            try (var engine = new AjdmEngine(database())) {
                var result = engine.resume(request.id()).completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
                assertEquals(server.sha256(), result.sha256()); assertArrayEquals(server.data(), Files.readAllBytes(result.target()));
            }
        }
    }

    @Test void aSavedByteChangedOnDiskWhilePausedIsStillCaught() throws Exception {
        try (var server = new FaultyRangeServer(2 * 1024 * 1024 + 17, FaultyRangeServer.Mode.SLOW)) {
            var request = request(server, 1);
            Path partial;
            try (var engine = new AjdmEngine(database())) {
                pauseAfterCheckpoint(engine.start(request));
                partial = engine.downloads().getFirst().partialPath();
            }
            // Inside the saved prefix: the resumed attempt keeps it, so only reading it back from disk can notice.
            try (var channel = FileChannel.open(partial, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                var one = ByteBuffer.allocate(1); channel.read(one, 1000); one.flip();
                channel.write(ByteBuffer.wrap(new byte[]{(byte) (one.get() ^ 0x5a)}), 1000); channel.force(true);
            }
            try (var engine = new AjdmEngine(database())) {
                var failure = assertThrows(ExecutionException.class,
                        () -> engine.resume(request.id()).completion().toCompletableFuture().get(20, TimeUnit.SECONDS));
                assertEquals(ErrorCode.CHECKSUM_MISMATCH, ((DownloadException) failure.getCause()).code());
                assertFalse(Files.exists(request.target()), "A file that failed verification must not be published");
            }
        }
    }

    /** Short bodies, stalls and dropped ranges rewind or discard saved coverage; each must reset a digest that passed it. */
    @ParameterizedTest
    @EnumSource(value = FaultyRangeServer.Mode.class, names = {"TRUNCATE_ONCE", "STALL_ONCE", "THROTTLE_ONCE", "IGNORE_RANGES_MIDWAY", "NO_RANGES_TRUNCATE_ONCE"})
    void coverageRewindsAndDiscardsStillVerifyTheFinalBytes(FaultyRangeServer.Mode mode) throws Exception {
        try (var server = new FaultyRangeServer(3 * 1024 * 1024 + 17, mode); var engine = new AjdmEngine(database())) {
            var result = engine.start(request(server, 4)).completion().toCompletableFuture().get(25, TimeUnit.SECONDS);
            assertEquals(server.sha256(), result.sha256()); assertArrayEquals(server.data(), Files.readAllBytes(result.target()));
        }
    }
}
