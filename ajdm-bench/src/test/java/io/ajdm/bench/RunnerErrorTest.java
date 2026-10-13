package io.ajdm.bench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class RunnerErrorTest {
    @TempDir Path directory;

    @Test void runnerShowsCatalogCodeAndActionWithoutSignedSource() throws Exception {
        Path target = directory.resolve("existing.bin");
        Files.writeString(target, "keep this file");
        var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("ajdm.test.classpath"),
                EngineRunner.class.getName(), "--state", directory.resolve("state/ajdm.db").toString(),
                "download", "https://example.com/file?token=secret", target.toString()).redirectErrorStream(true).start();
        try {
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            String output = new String(child.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(1, child.exitValue());
            assertTrue(output.contains("AJDM-2007: The destination already exists. Choose another filename."));
            assertFalse(output.contains("token=secret"));
            assertEquals("keep this file", Files.readString(target));
        } finally {
            if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
        }
    }
}
