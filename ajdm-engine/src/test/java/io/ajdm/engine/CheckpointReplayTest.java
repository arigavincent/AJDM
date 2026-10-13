package io.ajdm.engine;

import io.ajdm.model.*;
import io.ajdm.store.SqliteDownloadStore;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CheckpointReplayTest {
    @TempDir Path directory;
    @Test void failedReplayFlushKeepsOtherRangesDurableAndNeverClaimsTheDiscardedWindow() throws Exception {
        Path part = directory.resolve("part.ajdmpart");
        var request = new TransferRequest(UUID.randomUUID(), URI.create("https://example.com/file"), directory.resolve("result"), Map.of(), TransferOptions.defaults());
        AtomicLong written = new AtomicLong(), durable = new AtomicLong();
        AtomicBoolean failReplay = new AtomicBoolean();
        try (var store = new SqliteDownloadStore(directory.resolve("state/ajdm.db"));
             var channel = FileChannel.open(part, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
             var disks = Executors.newSingleThreadExecutor(); var timers = Executors.newSingleThreadScheduledExecutor()) {
            store.create(request, part); store.state(request.id(), DownloadState.PROBING, null);
            store.describe(request.id(), new ProbeResult(request.uri(), 16384, true, "\"v1\"", null));
            store.state(request.id(), DownloadState.DOWNLOADING, null);
            var writer = new BoundedFileWriter(channel, disks, 4096, 2, written);
            byte[] low = writer.acquire(); java.util.Arrays.fill(low, (byte) 1); writer.submit(low, 4096, 0);
            byte[] high = writer.acquire(); java.util.Arrays.fill(high, (byte) 2); writer.submit(high, 4096, 8192);
            CheckpointObserver observer = (phase, id, bytes) -> {
                if (failReplay.get() && phase == CheckpointObserver.Phase.BEFORE_FILE_FORCE) {
                    assertTrue(durable.get() <= written.get());
                    throw new java.io.IOException("Injected replay force failure");
                }
            };
            var checkpoints = new Checkpoints(request.id(), store, writer, durable, Duration.ofSeconds(30), timers, observer, failure -> {});
            try {
                checkpoints.flush(DownloadState.DOWNLOADING);
                assertEquals(8192, durable.get()); byte[] original = Files.readAllBytes(part);
                failReplay.set(true);
                assertThrows(DownloadException.class, () -> checkpoints.discardRange(0, 4096, DownloadState.DOWNLOADING));
                assertEquals(List.of(new ByteRange(8192, 12288)), store.get(request.id()).completed());
                assertEquals(4096, durable.get()); assertEquals(4096, written.get());
                assertArrayEquals(original, Files.readAllBytes(part), "Replay invalidation must not truncate another worker's data");
            } finally { checkpoints.stop(); }
        }
    }
}
