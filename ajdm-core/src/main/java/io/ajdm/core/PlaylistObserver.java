package io.ajdm.core;

import io.ajdm.model.*;
import java.io.IOException;

/** Durable playlist boundaries for process-kill qualification. */
@FunctionalInterface public interface PlaylistObserver {
    enum Boundary { CREATED, ITEM_INTENT, ITEM_ADMITTED, ITEM_COMPLETED }
    void reached(PlaylistJob job, PlaylistItem item, Boundary boundary) throws IOException;
    static PlaylistObserver none() { return (job, item, boundary) -> {}; }
}
