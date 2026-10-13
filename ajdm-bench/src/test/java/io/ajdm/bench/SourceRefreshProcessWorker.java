package io.ajdm.bench;

import io.ajdm.engine.*;
import io.ajdm.model.*;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Stops a separate JVM at a refresh durability boundary; the parent kills it without cleanup. */
public final class SourceRefreshProcessWorker {
    private SourceRefreshProcessWorker() {}
    private static void stop(String boundary, long bytes) throws java.io.IOException {
        System.out.println("REFRESH_CHECKPOINT " + boundary + " " + bytes); System.out.flush();
        try { new CountDownLatch(1).await(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException(e); }
    }
    public static void main(String[] args) throws Exception {
        Path database = Path.of(args[0]), target = Path.of(args[3]);
        UUID id = UUID.fromString(args[4]); String boundary = args[6];
        var refreshed = new AtomicBoolean(); var atRefresh = new AtomicLong();
        CheckpointObserver observer = (phase, download, bytes) -> {
            if (phase == CheckpointObserver.Phase.AFTER_SOURCE_REFRESH_COMMIT) {
                atRefresh.set(bytes); refreshed.set(true);
                if (boundary.equals("SOURCE_COMMIT")) stop(boundary, bytes);
            }
            if (boundary.equals("NEXT_CHECKPOINT") && phase == CheckpointObserver.Phase.AFTER_DB_COMMIT
                    && refreshed.get() && bytes > atRefresh.get()) stop(boundary, bytes);
        };
        var options = new TransferOptions(Duration.ofSeconds(3), Duration.ofSeconds(3), Duration.ofSeconds(3),
                4096, 4, 65536, 2, Duration.ofMillis(1), Duration.ofMillis(10), args[5], Duration.ofMillis(50),
                4, 65536, Boolean.parseBoolean(args[7]));
        try (var engine = new AjdmEngine(database, observer)) {
            engine.start(new TransferRequest(id, URI.create(args[1]), target, Map.of(), options), context -> {
                if (boundary.equals("CALLBACK")) stop(boundary, engine.downloads().getFirst().durableBytes());
                return new DownloadSource(URI.create(args[2]), Map.of("Authorization", "Bearer fresh-secret", "User-Agent", "AJDM-refresh-test"));
            }).completion().toCompletableFuture().get();
        }
    }
}
