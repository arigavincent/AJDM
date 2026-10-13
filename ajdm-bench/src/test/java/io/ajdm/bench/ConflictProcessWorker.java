package io.ajdm.bench;

import io.ajdm.engine.*;
import io.ajdm.model.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** Parent kills this process at a persisted destination/publication boundary. */
public final class ConflictProcessWorker {
    private ConflictProcessWorker() {}
    public static void main(String[] args) throws Exception {
        Path database = Path.of(args[0]), target = Path.of(args[2]);
        UUID id = UUID.fromString(args[3]);
        ConflictPolicy policy = ConflictPolicy.valueOf(args[5]);
        var boundary = CheckpointObserver.Phase.valueOf(args[6]);
        CheckpointObserver observer = (phase, download, bytes) -> {
            if (policy == ConflictPolicy.RENAME && phase == CheckpointObserver.Phase.AFTER_READY_COMMIT) {
                Files.writeString(target, "late original");
            }
            if (phase == boundary) {
                System.out.println("BOUNDARY " + bytes); System.out.flush();
                try { new java.util.concurrent.CountDownLatch(1).await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException("Boundary interrupted", e); }
            }
        };
        var options = new TransferOptions(Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(2),
                4096, 2, 65536, 2, Duration.ofMillis(1), Duration.ofMillis(10), args[4].equals("NONE") ? null : args[4], Duration.ofMillis(50));
        var files = args.length > 7 && args[7].equals("NO_LINKS") ? PortablePublicationTest.noLinks() : PublicationFiles.system();
        try (var engine = new AjdmEngine(database, EngineLimits.defaults(), observer, DiskSpaceProbe.fileStore(), files)) {
            engine.start(new TransferRequest(id, URI.create(args[1]), target, Map.of(), options, policy)).completion().toCompletableFuture().get();
        }
    }
}
