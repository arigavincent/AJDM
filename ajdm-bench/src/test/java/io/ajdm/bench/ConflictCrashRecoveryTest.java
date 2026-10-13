package io.ajdm.bench;

import io.ajdm.engine.*;
import io.ajdm.model.*;
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
class ConflictCrashRecoveryTest {
    @TempDir Path directory;
    @ParameterizedTest @ValueSource(strings = {"RENAME:AFTER_DESTINATION_COMMIT", "RENAME:AFTER_FINAL_LINK",
            "OVERWRITE:AFTER_READY_COMMIT", "OVERWRITE:AFTER_OVERWRITE_STAGE", "OVERWRITE:AFTER_FINAL_LINK",
            "OVERWRITE:AFTER_PUBLICATION_INTENT:NO_LINKS", "OVERWRITE:AFTER_FINAL_LINK:NO_LINKS"})
    void killedPublicationRecoversChosenDestinationWithoutNetwork(String test) throws Exception {
        String[] parts = test.split(":"); var policy = ConflictPolicy.valueOf(parts[0]);
        Path database = directory.resolve("state/ajdm.db"), target = directory.resolve("report.zip");
        UUID id = UUID.randomUUID();
        if (policy == ConflictPolicy.OVERWRITE) Files.writeString(target, "original");
        try (var server = new FaultyRangeServer(1024 * 1024 + 17, FaultyRangeServer.Mode.NORMAL)) {
            var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("ajdm.test.classpath"), ConflictProcessWorker.class.getName(),
                    database.toString(), server.uri().toString(), target.toString(), id.toString(),
                    parts.length > 2 ? "NONE" : server.sha256(), parts[0], parts[1], parts.length > 2 ? parts[2] : "SYSTEM")
                    .redirectErrorStream(true).start();
            try (var reader = Executors.newVirtualThreadPerTaskExecutor()) {
                var marker = reader.submit(() -> {
                    try (var lines = new BufferedReader(new InputStreamReader(child.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = lines.readLine()) != null) if (line.startsWith("BOUNDARY ")) return Long.parseLong(line.substring(9));
                    }
                    throw new AssertionError("Worker exited before publication boundary");
                });
                try {
                    assertEquals(server.data().length, marker.get(15, TimeUnit.SECONDS));
                    child.destroyForcibly(); assertTrue(child.waitFor(5, TimeUnit.SECONDS)); assertNotEquals(0, child.exitValue());
                } finally { if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); } }
            }
            int requests = server.requests();
            if (policy == ConflictPolicy.OVERWRITE && !parts[1].equals("AFTER_FINAL_LINK")) assertEquals("original", Files.readString(target));
            try (var engine = new AjdmEngine(database)) {
                var saved = engine.downloads().getFirst();
                assertTrue(saved.readyToPublish()); assertEquals(policy, saved.request().conflictPolicy());
                if (parts.length > 2) {
                    assertTrue(saved.publicationIntent()); assertEquals(server.sha256(), saved.actualSha256());
                    assertEquals(parts[1].equals("AFTER_PUBLICATION_INTENT"), Files.exists(saved.partialPath()));
                }
                Path expected = policy == ConflictPolicy.RENAME ? directory.resolve("report (1).zip") : target;
                assertEquals(expected, saved.request().target());
                var handle = engine.resume(id);
                var result = handle.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
                assertEquals(expected, result.target()); assertEquals(server.sha256(), result.sha256());
                assertArrayEquals(server.data(), Files.readAllBytes(result.target())); assertEquals(0, handle.progress().receivedBytes());
                assertEquals(requests, server.requests()); assertFalse(Files.exists(saved.partialPath()));
                assertFalse(Files.exists(saved.partialPath().resolveSibling(saved.partialPath().getFileName() + ".publish")));
                if (policy == ConflictPolicy.RENAME) assertEquals("late original", Files.readString(target));
            }
        }
    }
}
