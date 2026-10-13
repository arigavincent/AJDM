package io.ajdm.bench;

import io.ajdm.engine.AjdmEngine;
import io.ajdm.model.DownloadState;
import io.ajdm.model.TransferOptions;
import io.ajdm.model.TransferRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static io.ajdm.bench.FaultyRangeServer.Mode;

/** Local HTTP chaos; kernel packet loss/netem and real cellular networks require separate trials. */
class SeededChaosTest {
    @TempDir Path directory;
    @Test @Timeout(1800) void randomizedRecoverableFaultsPublishOnlyBitIdenticalFiles() throws Exception {
        int runs = Integer.getInteger("ajdm.chaosRuns", 25);
        assertTrue(runs >= 1 && runs <= 10000, "chaosRuns must be 1..10000");
        var random = new Random(0x414a444d2026L);
        var modes = new Mode[]{Mode.NORMAL, Mode.HEAD_REJECTED, Mode.NO_RANGES, Mode.UNKNOWN_LENGTH,
                Mode.NO_VALIDATOR, Mode.TRUNCATE_ONCE, Mode.STALL_ONCE, Mode.THROTTLE_ONCE,
                Mode.IGNORE_RANGES_MIDWAY, Mode.NO_RANGES_TRUNCATE_ONCE};
        for (int run = 0; run < runs; run++) {
            Mode mode = modes[run < modes.length ? run : random.nextInt(modes.length)];
            int size = 16385 + random.nextInt(262144);
            Path work = directory.resolve("case-" + run), target = work.resolve("download.bin");
            String context = "seed=0x414a444d2026 run=" + run + " mode=" + mode + " size=" + size;
            try (var server = new FaultyRangeServer(size, mode); var engine = new AjdmEngine(work.resolve("state/ajdm.db"))) {
                int buffer = 1024 << random.nextInt(5), connections = 1 << random.nextInt(4);
                var options = new TransferOptions(Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofMillis(150),
                        buffer, 1 + random.nextInt(8), 16384L << random.nextInt(4), 4,
                        Duration.ofMillis(1), Duration.ofMillis(8), server.sha256(), Duration.ofMillis(20), connections, 16384);
                if (random.nextBoolean()) options = options.withAdaptiveParallelism(connections, 16384);
                var request = new TransferRequest(UUID.randomUUID(), server.uri(), target, Map.of(), options);
                var handle = engine.start(request);
                var result = handle.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
                assertArrayEquals(server.data(), Files.readAllBytes(result.target()), context);
                assertEquals(server.sha256(), result.sha256(), context);
                assertEquals(size, result.bytes(), context);
                assertEquals(DownloadState.COMPLETED, handle.progress().state(), context);
                var saved = engine.downloads().getFirst();
                assertEquals(DownloadState.COMPLETED, saved.state(), context);
                assertEquals(size, saved.durableBytes(), context);
                assertFalse(Files.exists(saved.partialPath()), context);
            }
            if (runs > 25 && (run + 1) % 100 == 0) System.out.println("Verified " + (run + 1) + "/" + runs + " seeded HTTP chaos outputs");
        }
    }
}
