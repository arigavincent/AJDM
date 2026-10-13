package io.ajdm.core;

import io.ajdm.model.MediaJob;
import java.io.IOException;

/** Durable boundary hook for independent process-kill qualification. */
@FunctionalInterface public interface MediaJobObserver {
    enum Boundary { PREPARED, MERGING, READY, PUBLISHED }
    void reached(MediaJob job, Boundary boundary) throws IOException;
    static MediaJobObserver none() { return (job, boundary) -> {}; }
}
