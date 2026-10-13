package io.ajdm.bench;

import io.ajdm.engine.AjdmEngine;
import io.ajdm.engine.CheckpointObserver;
import io.ajdm.model.TransferOptions;
import io.ajdm.model.TransferRequest;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

/** Separate JVM used exclusively by process-kill tests; no graceful shutdown hook is installed. */
public final class ResumeProcessWorker {
    private ResumeProcessWorker() {}
    public static void main(String[] args) throws Exception {
        Path database = Path.of(args[0]), target = Path.of(args[2]);
        UUID id = UUID.fromString(args[3]);
        boolean random = "RANDOM".equals(args[5]);
        var phase = random ? CheckpointObserver.Phase.AFTER_DB_COMMIT : CheckpointObserver.Phase.valueOf(args[5]);
        long minimumBytes = args.length > 6 ? Long.parseLong(args[6]) : 0;
        CheckpointObserver observer = (at, download, bytes) -> {
            if (at != phase || bytes == 0 && at != CheckpointObserver.Phase.AFTER_PREALLOCATION || bytes < minimumBytes) return;
            System.out.println("CHECKPOINT " + at + " " + bytes); System.out.flush();
            // Stop at the selected durable boundary so a delayed parent always kills a live process.
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException(e); }
        };
        var options = new TransferOptions(Duration.ofSeconds(3), Duration.ofSeconds(3), Duration.ofSeconds(3),
                4096, 4, 262144, 2, Duration.ofMillis(1), Duration.ofMillis(10), args[4], Duration.ofMillis(50));
        if (args.length > 7) options = options.withParallelism(Integer.parseInt(args[7]), 65536);
        if (args.length > 8 && Boolean.parseBoolean(args[8])) options = options.withAdaptiveParallelism(options.connections(), options.minSegmentBytes());
        try (var engine = new AjdmEngine(database, observer)) {
            engine.start(new TransferRequest(id, URI.create(args[1]), target, Map.of(), options))
                    .completion().toCompletableFuture().get();
        }
    }
}
