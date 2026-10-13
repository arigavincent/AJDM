package io.ajdm.bench;

import io.ajdm.engine.*;
import io.ajdm.model.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class SourceRestartTest {
    @TempDir Path directory;
    @Test void explicitRestartAfterReopenUsesNewIdAndPartialKeepingOldBytesAndReason() throws Exception {
        Path database = directory.resolve("state/ajdm.db");
        try (var server = new FaultyRangeServer(1024 * 1024 + 17, FaultyRangeServer.Mode.SLOW)) {
            var options = new TransferOptions(java.time.Duration.ofSeconds(2), java.time.Duration.ofSeconds(2), java.time.Duration.ofSeconds(2),
                    4096, 2, 65536, 2, java.time.Duration.ZERO, java.time.Duration.ZERO, server.sha256(), java.time.Duration.ofMillis(50));
            var request = new TransferRequest(java.util.UUID.randomUUID(), server.uri(), directory.resolve("result.bin"), java.util.Map.of(), options);
            Path partial; byte[] old; long durable;
            try (var engine = new AjdmEngine(database)) {
                var handle = engine.start(request);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                while (handle.progress().durableBytes() < 65536 && System.nanoTime() < deadline) Thread.sleep(10);
                assertTrue(handle.progress().durableBytes() >= 65536); handle.pause().toCompletableFuture().get();
                partial = handle.partialPath().orElseThrow(); old = Files.readAllBytes(partial); durable = handle.progress().durableBytes();
                server.etag("\"new-version\"");
                var changed = engine.resume(request.id());
                assertThrows(ExecutionException.class, () -> changed.completion().toCompletableFuture().get());
                assertEquals(DownloadState.PAUSED, changed.progress().state());
            }
            try (var engine = new AjdmEngine(database)) {
                var restarted = engine.restart(request.id());
                assertNotEquals(request.id(), restarted.id()); assertNotEquals(partial, restarted.partialPath().orElseThrow());
                var result = restarted.completion().toCompletableFuture().get();
                assertArrayEquals(server.data(), Files.readAllBytes(result.target()));
                assertArrayEquals(old, Files.readAllBytes(partial));
                var saved = engine.downloads().stream().filter(r -> r.request().id().equals(request.id())).findFirst().orElseThrow();
                assertEquals(durable, saved.durableBytes()); assertEquals(ErrorCode.SOURCE_CHANGED, saved.error());
                assertEquals(DownloadState.PAUSED, saved.state());
                assertThrows(IllegalStateException.class, () -> engine.restart(restarted.id()));
            }
        }
    }
}
