package io.ajdm.bench;

import io.ajdm.engine.AjdmEngine;
import io.ajdm.model.DownloadState;
import io.ajdm.model.DownloadException;
import io.ajdm.model.ErrorCode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class ReprobeCrashRecoveryTest {
    @TempDir Path directory;
    @ParameterizedTest @ValueSource(strings = {"LATE_416_ONCE", "LATE_HTTP_403_ONCE", "LATE_HTTP_410_ONCE", "CHANGE"})
    void killedReprobeReusesSavedWindowsWithMatchingIdentity(String mode) throws Exception {
        Path database = directory.resolve("state/ajdm.db"), target = directory.resolve("result.bin"); UUID id = UUID.randomUUID();
        try (var server = new RetryTableServer(mode)) {
            var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("ajdm.test.classpath"), ReprobeProcessWorker.class.getName(),
                    database.toString(), server.uri().toString(), target.toString(), id.toString(), server.hash(), mode).redirectErrorStream(true).start();
            try (var readers = Executors.newVirtualThreadPerTaskExecutor()) {
                var marker = readers.submit(() -> {
                    try (var lines = new BufferedReader(new InputStreamReader(child.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = lines.readLine()) != null) if (line.startsWith("REPROBE ")) return Long.parseLong(line.substring(8));
                    }
                    throw new AssertionError("Worker exited before re-probe commit");
                });
                try {
                    assertEquals(65536, marker.get(15, TimeUnit.SECONDS));
                    child.destroyForcibly(); assertTrue(child.waitFor(5, TimeUnit.SECONDS)); assertNotEquals(0, child.exitValue());
                } finally { if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); } }
            }
            try (var engine = new AjdmEngine(database)) {
                var saved = engine.downloads().getFirst(); assertEquals(65536, saved.durableBytes());
                assertEquals(DownloadState.PAUSED, saved.state()); assertEquals("\"v1\"", saved.probe().etag());
                if (mode.equals("CHANGE")) {
                    assertEquals(ErrorCode.SOURCE_CHANGED, saved.error());
                    byte[] old = Files.readAllBytes(saved.partialPath());
                    var failed = assertThrows(java.util.concurrent.ExecutionException.class,
                            () -> engine.resume(id).completion().toCompletableFuture().get());
                    assertEquals(ErrorCode.SOURCE_CHANGED, ((DownloadException) failed.getCause()).code());
                    assertArrayEquals(old, Files.readAllBytes(saved.partialPath())); assertFalse(Files.exists(target));
                    assertEquals(ErrorCode.SOURCE_CHANGED, engine.downloads().getFirst().error());
                    return;
                }
                var handle = engine.resume(id); var result = handle.completion().toCompletableFuture().get();
                assertArrayEquals(server.data, Files.readAllBytes(result.target())); assertEquals(server.hash(), result.sha256());
                assertEquals(server.data.length - 65536, handle.progress().receivedBytes());
            }
        }
    }
}
