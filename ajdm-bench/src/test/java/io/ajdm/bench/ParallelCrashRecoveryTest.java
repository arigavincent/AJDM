package io.ajdm.bench;

import io.ajdm.engine.AjdmEngine;
import io.ajdm.model.DownloadState;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ParallelCrashRecoveryTest {
    @TempDir Path directory;
    @ParameterizedTest @ValueSource(booleans = {false, true}) @Timeout(1800)
    void killedParallelWorkersRecoverDisjointCoverageWithoutFetchingSavedBytes(boolean adaptive) throws Exception {
        int runs = Integer.getInteger(adaptive ? "ajdm.adaptiveCrashRuns" : "ajdm.parallelCrashRuns", 10);
        assertTrue(runs >= 1 && runs <= 2000);
        var random = new Random(50641);
        for (int run = 0; run < runs; run++) {
            Path root = directory.resolve((adaptive ? "adaptive-kill-" : "fixed-kill-") + run); Files.createDirectories(root);
            Path database = root.resolve("state/ajdm.db"), target = root.resolve("result.bin");
            UUID id = UUID.randomUUID();
            try (var server = new FaultyRangeServer(1024 * 1024 + 17, FaultyRangeServer.Mode.BLOCK_FIRST_SEGMENT)) {
                String javaBinary = Path.of(System.getProperty("java.home"), "bin", "java").toString();
                Process child = new ProcessBuilder(javaBinary, "--enable-native-access=ALL-UNNAMED", "-cp",
                        System.getProperty("ajdm.test.classpath"), ResumeProcessWorker.class.getName(), database.toString(),
                        server.uri().toString(), target.toString(), id.toString(), server.sha256(), "RANDOM",
                        Integer.toString(adaptive ? 576 * 1024 + random.nextInt(96 * 1024) : random.nextInt(384 * 1024)), "4", Boolean.toString(adaptive)).redirectErrorStream(true).start();
                try (var reader = Executors.newVirtualThreadPerTaskExecutor()) {
                    var marker = reader.submit(() -> {
                        try (var lines = new BufferedReader(new InputStreamReader(child.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = lines.readLine()) != null) if (line.startsWith("CHECKPOINT ")) return Long.parseLong(line.substring(line.lastIndexOf(' ') + 1));
                        }
                        throw new AssertionError("Worker exited before a disjoint checkpoint");
                    });
                    try {
                        assertTrue(marker.get(15, TimeUnit.SECONDS) > 0);
                        Thread.sleep(random.nextInt(40)); child.destroyForcibly();
                        assertTrue(child.waitFor(5, TimeUnit.SECONDS)); assertNotEquals(0, child.exitValue());
                    } finally { if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); } }
                }
                server.releaseFirstSegment();
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (server.activeTransfers() > 0 && System.nanoTime() < until) Thread.sleep(10);
                assertEquals(0, server.activeTransfers());
                int before = server.requestedRanges().size();
                try (var engine = new AjdmEngine(database)) {
                    var saved = engine.downloads().getFirst();
                    assertEquals(DownloadState.PAUSED, saved.state()); assertEquals(0, saved.prefixLength());
                    assertTrue(saved.durableEnd() > saved.durableBytes());
                    assertTrue(Files.size(saved.partialPath()) >= saved.durableEnd()); assertEquals(4, saved.request().options().connections());
                    assertEquals(adaptive, saved.request().options().adaptive());
                    var handle = engine.resume(id);
                    var result = handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
                    assertEquals(server.sha256(), result.sha256()); assertArrayEquals(server.data(), Files.readAllBytes(result.target()));
                    assertEquals(result.bytes() - saved.durableBytes(), handle.progress().receivedBytes());
                    ParallelIntegrationTest.assertNoSavedBytesRequested(saved.completed(), server.requestedRanges().subList(before, server.requestedRanges().size()));
                    assertEquals(DownloadState.COMPLETED, engine.downloads().getFirst().state()); assertFalse(Files.exists(saved.partialPath()));
                }
            }
            if (runs > 10 && (run + 1) % 10 == 0) System.out.println("Recovered " + (run + 1) + "/" + runs + (adaptive ? " adaptive" : " fixed") + " process kills from disjoint ranges");
        }
    }
}
