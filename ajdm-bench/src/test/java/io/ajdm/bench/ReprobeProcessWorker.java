package io.ajdm.bench;

import io.ajdm.engine.*;
import io.ajdm.model.*;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** Parent kills this JVM after a coordinated re-probe commit with retained payload. */
public final class ReprobeProcessWorker {
    private ReprobeProcessWorker() {}
    public static void main(String[] args) throws Exception {
        boolean sourcePause = args.length > 5 && args[5].equals("CHANGE");
        var options = new TransferOptions(Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(2),
                4096, 2, 65536, 0, Duration.ZERO, Duration.ZERO, args[4], sourcePause ? Duration.ofSeconds(30) : Duration.ofMillis(50));
        CheckpointObserver observer = (phase, id, bytes) -> {
            if (phase == (sourcePause ? CheckpointObserver.Phase.AFTER_DB_COMMIT : CheckpointObserver.Phase.AFTER_REPROBE_COMMIT)) {
                System.out.println("REPROBE " + bytes); System.out.flush();
                try { new java.util.concurrent.CountDownLatch(1).await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException(e); }
            }
        };
        try (var engine = new AjdmEngine(Path.of(args[0]), observer)) {
            engine.start(new TransferRequest(UUID.fromString(args[3]), URI.create(args[1]), Path.of(args[2]), Map.of(), options))
                    .completion().toCompletableFuture().get();
        }
    }
}
