package io.ajdm.media;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class PlaylistProcessTest {
    @Test void flatListingUsesSimulationSafeArgumentsAndKeepsUnavailableEntry() throws Exception {
        var command = List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp", System.getProperty("ajdm.media.test.classpath"), FakeExtractor.class.getName());
        try (var tool = new YtDlpExtractor(command, Duration.ofSeconds(5), 100_000)) {
            var list = tool.playlist(URI.create("https://page.test/list?literal=$()"), () -> false);
            assertEquals(2, list.entries().size()); assertNotNull(list.entries().getLast().unavailable());
            for (String flag : List.of("--simulate", "--skip-download", "--no-check-formats", "--flat-playlist", "--yes-playlist", "--ignore-errors", "--ignore-config", "--no-plugin-dirs")) assertTrue(list.title().contains(flag));
            assertFalse(list.title().contains("--no-playlist")); assertTrue(list.title().endsWith("|--|https://page.test/list?literal=$()"));
        }
    }
}
