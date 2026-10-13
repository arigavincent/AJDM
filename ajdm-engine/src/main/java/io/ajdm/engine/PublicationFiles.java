package io.ajdm.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Filesystem publication operations. Implementations must preserve atomicity; no copy fallback. */
public interface PublicationFiles {
    /** Atomically create a new name, failing if it already exists. */
    void link(Path target, Path partial) throws IOException;
    /** Atomically replace a destination with the source, or fail with both files intact. */
    void atomicReplace(Path source, Path target) throws IOException;
    static PublicationFiles system() {
        return new PublicationFiles() {
            @Override public void link(Path target, Path partial) throws IOException { Files.createLink(target, partial); }
            @Override public void atomicReplace(Path source, Path target) throws IOException {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
        };
    }
}
