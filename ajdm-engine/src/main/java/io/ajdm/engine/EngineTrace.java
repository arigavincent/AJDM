package io.ajdm.engine;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Opt-in diagnostic timeline for measuring real links: {@code -Dajdm.trace=FILE} appends one line per engine event
 * (lease, steal, retry, shed) with seconds since process start. Off by default; never records URLs or headers.
 */
final class EngineTrace {
    private static final long ORIGIN = System.nanoTime();
    private static final PrintStream OUT = open(System.getProperty("ajdm.trace"));
    private EngineTrace() {}

    static boolean enabled() { return OUT != null; }

    static void event(String name, int worker, String detail) {
        if (OUT == null) return;
        OUT.printf(java.util.Locale.ROOT, "%.3f w%d %s %s%n", (System.nanoTime() - ORIGIN) / 1e9, worker, name, detail);
    }

    private static PrintStream open(String path) {
        if (path == null || path.isBlank()) return null;
        try {
            return new PrintStream(Files.newOutputStream(Path.of(path), StandardOpenOption.CREATE, StandardOpenOption.APPEND),
                    true, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) { return null; }
    }
}
