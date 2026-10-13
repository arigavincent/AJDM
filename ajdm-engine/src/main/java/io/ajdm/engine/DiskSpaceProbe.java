package io.ajdm.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads current usable capacity of the destination filesystem; never reserves space. */
@FunctionalInterface
public interface DiskSpaceProbe {
    long usableBytes(Path directory) throws IOException;

    static DiskSpaceProbe fileStore() { return directory -> Files.getFileStore(directory).getUsableSpace(); }
}
