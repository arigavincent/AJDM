package io.ajdm.bench;

import io.ajdm.engine.AjdmEngine;
import io.ajdm.model.DownloadState;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

class SourceRefreshCrashRecoveryTest {
    @TempDir Path directory;
    static Stream<Arguments> boundaries() {
        return Stream.of(false, true).flatMap(adaptive -> Stream.of("CALLBACK", "SOURCE_COMMIT", "NEXT_CHECKPOINT")
                .map(boundary -> Arguments.of(adaptive, boundary)));
    }
    @ParameterizedTest @MethodSource("boundaries") @Timeout(600)
    void processKillRetainsAnAtomicValidatedSourceAndOnlyDownloadsGaps(boolean adaptive, String boundary) throws Exception {
        int runs = Integer.getInteger("ajdm.refreshCrashRuns", 3);
        assertTrue(runs >= 1 && runs <= 100);
        var random = new Random(97351);
        for (int run = 0; run < runs; run++) {
            Path root = directory.resolve(boundary + "-" + adaptive + "-" + run); Files.createDirectories(root);
            Path database = root.resolve("state/ajdm.db"), target = root.resolve("result.bin"); UUID id = UUID.randomUUID();
            try (var server = new ExpiringSourceServer()) {
                server.slowReplacement = true;
                String javaBinary = Path.of(System.getProperty("java.home"), "bin", "java").toString();
                Process child = new ProcessBuilder(javaBinary, "--enable-native-access=ALL-UNNAMED", "-cp",
                        System.getProperty("ajdm.test.classpath"), SourceRefreshProcessWorker.class.getName(), database.toString(),
                        server.uri(false).toString(), server.uri(true).toString(), target.toString(), id.toString(),
                        server.sha256(), boundary, Boolean.toString(adaptive)).redirectErrorStream(true).start();
                try (var reader = Executors.newVirtualThreadPerTaskExecutor()) {
                    var marker = reader.submit(() -> {
                        try (var lines = new BufferedReader(new InputStreamReader(child.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                            String line; var diagnostics = new StringBuilder();
                            while ((line = lines.readLine()) != null) {
                                if (line.startsWith("REFRESH_CHECKPOINT ")) return Long.parseLong(line.substring(line.lastIndexOf(' ') + 1));
                                diagnostics.append(line).append('\n');
                            }
                            throw new AssertionError("Worker exited before boundary: " + diagnostics);
                        }
                    });
                    try {
                        assertTrue(marker.get(15, TimeUnit.SECONDS) > 0);
                        Thread.sleep(random.nextInt(30)); child.destroyForcibly();
                        assertTrue(child.waitFor(5, TimeUnit.SECONDS)); assertNotEquals(0, child.exitValue());
                    } finally { if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); } }
                }
                server.initiallyExpired = true;
                int before = server.replacementRanges.size();
                try (var engine = new AjdmEngine(database)) {
                    var saved = engine.downloads().getFirst();
                    assertEquals(DownloadState.PAUSED, saved.state()); assertTrue(saved.durableBytes() > 0);
                    boolean committed = !boundary.equals("CALLBACK");
                    assertEquals(server.uri(committed), saved.request().uri());
                    assertEquals(committed ? Map.of("User-Agent", "AJDM-refresh-test") : Map.of(), saved.request().headers());
                    assertEquals(adaptive, saved.request().options().adaptive());
                    var calls = new AtomicInteger();
                    var handle = engine.resume(id, Map.of("Authorization", "Bearer fresh-secret"), context -> {
                        calls.incrementAndGet(); return server.replacement();
                    });
                    var result = handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
                    assertEquals(committed ? 0 : 1, calls.get());
                    assertArrayEquals(server.data, Files.readAllBytes(target)); assertEquals(server.sha256(), result.sha256());
                    assertEquals(result.bytes() - saved.durableBytes(), handle.progress().receivedBytes());
                    ParallelIntegrationTest.assertNoSavedBytesRequested(saved.completed(), server.replacementRanges.subList(before, server.replacementRanges.size()));
                    assertEquals(DownloadState.COMPLETED, engine.downloads().getFirst().state());
                }
            }
        }
    }
}
