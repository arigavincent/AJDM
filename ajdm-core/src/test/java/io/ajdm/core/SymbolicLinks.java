package io.ajdm.core;

import java.io.IOException;
import java.nio.file.*;
import java.util.Locale;
import org.junit.jupiter.api.Assumptions;

/** Windows grants symbolic-link creation only with Developer Mode or elevation; elsewhere a failure is a real failure. */
final class SymbolicLinks {
    private SymbolicLinks() { }
    static Path create(Path link, Path target) throws IOException {
        try { return Files.createSymbolicLink(link, target); }
        catch (FileSystemException e) {
            if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) Assumptions.abort("Symbolic links need Windows Developer Mode or elevation");
            throw e;
        }
    }
}
