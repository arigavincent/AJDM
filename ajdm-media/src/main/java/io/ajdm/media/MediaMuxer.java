package io.ajdm.media;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/** Reads completed local streams only. Implementations never fetch payloads or transcode. */
public interface MediaMuxer extends AutoCloseable {
    void mux(Path video, String videoExt, Path audio, String audioExt, Path output, String container,
             BooleanSupplier canceled) throws IOException, InterruptedException;
    @Override default void close() {}
}
