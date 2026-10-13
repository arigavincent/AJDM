package io.ajdm.bench;

import io.ajdm.engine.AjdmEngine;
import io.ajdm.engine.CheckpointObserver;
import io.ajdm.model.DownloadState;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(90)
class CrashRecoveryTest {
    @TempDir Path directory;
    @ParameterizedTest
    @EnumSource(value = CheckpointObserver.Phase.class, names = {"BEFORE_FILE_FORCE", "AFTER_FILE_FORCE", "AFTER_DB_COMMIT", "AFTER_READY_COMMIT", "AFTER_FINAL_LINK", "AFTER_PREALLOCATION"})
    void killAtEachDurabilityBoundaryRecoversBitIdentically(CheckpointObserver.Phase phase) throws Exception {
        runKilledDownload(directory, phase.name(), 0, 0);
    }
    @Test @Timeout(1800)
    void seededKillsAtVariedOffsetsRecoverWithoutRestartingFromZero() throws Exception {
        var random = new Random(81437);
        int runs = Integer.getInteger("ajdm.crashRuns", 10);
        assertTrue(runs >= 1 && runs <= 5000);
        for (int run = 0; run < runs; run++) {
            runKilledDownload(directory.resolve("kill-" + run), "RANDOM", random.nextInt(40), random.nextInt(768 * 1024));
            if (runs > 10 && (run + 1) % 25 == 0) System.out.println("Recovered " + (run + 1) + "/" + runs + " killed workers with matching SHA-256");
        }
    }
    private void runKilledDownload(Path runDirectory, String phase, int delay, long minimumBytes) throws Exception {
        Files.createDirectories(runDirectory);
        Path database = runDirectory.resolve("state/ajdm.db"), target = runDirectory.resolve("download.bin");
        UUID id = UUID.randomUUID();
        try (var server = new FaultyRangeServer(1024 * 1024 + 17, FaultyRangeServer.Mode.SLOW)) {
            String javaBinary = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            Process child = new ProcessBuilder(javaBinary, "--enable-native-access=ALL-UNNAMED", "-cp",
                    System.getProperty("ajdm.test.classpath"), ResumeProcessWorker.class.getName(),
                    database.toString(), server.uri().toString(), target.toString(), id.toString(), server.sha256(), phase, Long.toString(minimumBytes))
                    .redirectErrorStream(true).start();
            long marker;
            try (var reader = Executors.newVirtualThreadPerTaskExecutor()) {
                var output = reader.submit(() -> {
                    try (var lines = new BufferedReader(new InputStreamReader(child.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = lines.readLine()) != null) {
                            if (line.startsWith("CHECKPOINT ")) return Long.parseLong(line.substring(line.lastIndexOf(' ') + 1));
                        }
                    }
                    throw new AssertionError("Worker exited before reaching checkpoint");
                });
                try {
                    marker = output.get(15, TimeUnit.SECONDS);
                    if (delay > 0) Thread.sleep(delay);
                    child.destroyForcibly(); assertTrue(child.waitFor(5, TimeUnit.SECONDS));
                    assertNotEquals(0, child.exitValue(), "Test must kill the worker rather than finish gracefully");
                } finally {
                    if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
                }
            }
            int rangesBeforeResume = server.requestedRanges().size(), requestsBeforeResume = server.requests();
            try (var engine = new AjdmEngine(database)) {
                var saved = engine.downloads().getFirst();
                assertEquals(DownloadState.PAUSED, saved.state());
                assertTrue(saved.durableBytes() <= Files.size(saved.partialPath()));
                assertEquals(server.data().length, Files.size(saved.partialPath()));
                if (phase.equals("AFTER_PREALLOCATION")) {
                    assertEquals(0, saved.durableBytes()); assertEquals(0, marker); assertEquals(0, server.transferRequests());
                }
                if (phase.equals("BEFORE_FILE_FORCE") || phase.equals("AFTER_FILE_FORCE")) {
                    assertEquals(0, saved.durableBytes(), "Uncommitted bytes must not appear in resume coverage");
                    assertTrue(marker > saved.durableBytes());
                } else assertTrue(saved.durableBytes() >= marker);
                var resumed = engine.resume(id);
                var result = resumed.completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
                assertArrayEquals(server.data(), Files.readAllBytes(result.target()));
                assertEquals(server.sha256(), result.sha256());
                assertEquals(DownloadState.COMPLETED, engine.downloads().getFirst().state());
                assertEquals(result.bytes(), resumed.progress().durableBytes());
                assertFalse(Files.exists(saved.partialPath()));
                if (phase.equals("AFTER_FINAL_LINK") || phase.equals("AFTER_READY_COMMIT")) {
                    assertEquals(requestsBeforeResume, server.requests(), "Verified-file recovery needs no network requests");
                    assertEquals(0, resumed.progress().receivedBytes());
                } else if (saved.durableBytes() < result.bytes()) {
                    assertEquals(saved.durableBytes(), server.requestedRanges().get(rangesBeforeResume).start());
                    assertEquals(result.bytes() - saved.durableBytes(), resumed.progress().receivedBytes());
                } else {
                    assertEquals(rangesBeforeResume, server.requestedRanges().size(), "Complete coverage has no remaining payload range");
                    assertEquals(0, resumed.progress().receivedBytes());
                }
            }
        }
    }
}
