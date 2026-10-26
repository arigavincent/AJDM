package io.ajdm.media;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/** Reads completed local streams only. Implementations never fetch payloads or transcode. */
public interface MediaMuxer extends AutoCloseable {
    void mux(Path video, String videoExt, Path audio, String audioExt, Path output, String container,
             BooleanSupplier canceled) throws IOException, InterruptedException;
    /** One completed local stream copied into another container without re-encoding. */
    default void remux(Path input, String ext, Path output, String container, BooleanSupplier canceled) throws IOException, InterruptedException {
        throw new io.ajdm.model.DownloadException(io.ajdm.model.ErrorCode.FEATURE_UNAVAILABLE, "Remux unavailable");
    }
    @Override default void close() {}
}
